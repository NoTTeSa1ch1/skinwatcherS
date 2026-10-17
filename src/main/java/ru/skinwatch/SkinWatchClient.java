package ru.skinwatch;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SkinWatchClient implements ClientModInitializer {

    /** true = писать в общий чат сервера (увидят все), false = сообщение видно только тебе. */
    private static final boolean SEND_TO_PUBLIC_CHAT = false;

    /** Допуск по цвету для каждого канала (0..255). */
    private static final int COLOR_TOLERANCE = 24;
    /** Сколько из 64 пикселей лица должно совпасть. */
    private static final int MIN_MATCHED = 58;
    /** Как часто проверять TAB (тики, 20 = раз в секунду). */
    private static final int CHECK_INTERVAL = 20;

    /** Эталонные лица 8x8 (RGB) запрещённых скинов. Совпадение с любым из них = нарушение. */
    private static final int[][] REFS = {
        // скин 1: чёрные волосы, усики
        {
            0x000000, 0x000000, 0x141313, 0x000000, 0x000000, 0x141313, 0x000000, 0x131313,
            0xD4AA7B, 0xD4AA7B, 0xD4AA7B, 0xD4AA7B, 0x000000, 0x000000, 0x131313, 0x000000,
            0xD4AA7C, 0xB68859, 0xB68859, 0xD4AA7C, 0xD4AA7D, 0xB68859, 0x000000, 0x000000,
            0xD4AA7C, 0xE5E6E5, 0x151414, 0xD4AA7D, 0xD4AA7D, 0x141313, 0xE5E6E5, 0x000000,
            0xD4AA7D, 0xD4AA7D, 0xD4AA7D, 0xD4AA7D, 0xD4AA7D, 0xD4AA7D, 0xD4AA7D, 0xD4AA7D,
            0xD4AA7C, 0xD4AA7D, 0xD4AA7D, 0x000000, 0x000000, 0xD4AA7D, 0xD4AA7D, 0xD4AA7D,
            0xD4AA7C, 0xD4AA7D, 0xD4AA7D, 0xB68859, 0xB68859, 0xD4AA7C, 0xD4AA7D, 0xD4AA7D,
            0xD4AA7D, 0xD4AA7D, 0xD4AA7D, 0xD4AA7D, 0xD4AA7D, 0xD4AA7D, 0xD4AA7D, 0xD4AA7D
        },
        // скин 2: фуражка
        {
            0x3E260A, 0x3E260A, 0x42403F, 0x42403F, 0x42403F, 0x42403F, 0x3E260A, 0x3E260A,
            0x643D10, 0x643D10, 0x643D10, 0x5B5958, 0x5B5958, 0x643D10, 0x643D10, 0x643D10,
            0x080707, 0x080707, 0x080707, 0x080707, 0x080707, 0x080707, 0x080707, 0x080707,
            0xCD9B65, 0xE5E6E5, 0x151414, 0xCD9B65, 0xCD9B65, 0x151414, 0xE5E6E5, 0xCD9B65,
            0xCD9B65, 0xCD9B65, 0xCD9B65, 0xCD9B65, 0xCD9B65, 0xCD9B65, 0xCD9B65, 0xCD9B65,
            0xCD9B65, 0xCD9B65, 0xCD9B65, 0x0A0A0A, 0x0A0A0A, 0xCD9B65, 0xCD9B65, 0xCD9B65,
            0xCD9B65, 0xCD9B65, 0xCD9B65, 0x8D6B46, 0x8D6B46, 0xCD9B65, 0xCD9B65, 0xCD9B65,
            0xCD9B65, 0xCD9B65, 0xCD9B65, 0xCD9B65, 0xCD9B65, 0xCD9B65, 0xCD9B65, 0xCD9B65
        }
    };

    private static final String ABSENT = "";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "SkinWatch-Downloader");
        t.setDaemon(true);
        return t;
    });

    /** base64-значение свойства "textures" -> URL скина (ABSENT, если скина нет). */
    private final Map<String, String> urlByValue = new ConcurrentHashMap<>();
    /** URL скина -> запрещён ли он. */
    private final Map<String, Boolean> verdicts = new ConcurrentHashMap<>();
    /** URL, которые сейчас скачиваются. */
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    /** Ники, о которых уже написали. */
    private final Set<String> reported = ConcurrentHashMap.newKeySet();
    private int ticks = 0;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reported.clear());
    }

    private void onTick(MinecraftClient mc) {
        if (mc.player == null || mc.getNetworkHandler() == null) return;
        if (++ticks % CHECK_INTERVAL != 0) return;

        for (PlayerListEntry entry : mc.getNetworkHandler().getPlayerList()) {
            Object profile = entry.getProfile();
            String name = asString(call(profile, "name", "getName"));
            if (name == null || reported.contains(name)) continue;

            String value = findTexturesValue(profile);
            if (value == null) continue;

            String url = urlByValue.computeIfAbsent(value, SkinWatchClient::extractSkinUrl);
            if (url.isEmpty()) continue;

            Boolean banned = verdicts.get(url);
            if (banned == null) {
                if (pending.add(url)) pool.submit(() -> download(url));
            } else if (banned) {
                reported.add(name);
                String msg = "найден запрещённый скин у игрока " + name;
                if (SEND_TO_PUBLIC_CHAT) {
                    mc.getNetworkHandler().sendChatMessage(msg);
                } else {
                    mc.player.sendMessage(Text.literal("[SkinWatch] " + msg).formatted(Formatting.RED, Formatting.BOLD), false);
                }
            }
        }
    }

    /** Скачивает PNG скина в фоновом потоке и сохраняет вердикт. */
    private void download(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            boolean banned = false;
            if (resp.statusCode() == 200 && resp.body().length < 2_000_000) {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(resp.body()));
                banned = img != null && matches(img);
            }
            verdicts.put(url, banned);
        } catch (Throwable t) {
            verdicts.put(url, false);
        } finally {
            pending.remove(url);
        }
    }

    /** Проверяет лицо скина (база + слой шляпы) на совпадение с любым эталоном. Поддерживает HD-скины. */
    private static boolean matches(BufferedImage img) {
        int s = Math.max(1, img.getWidth() / 64);
        if (img.getWidth() < 64 || img.getHeight() < 32 * s) return false;

        int[] face = new int[64];
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int base = img.getRGB((8 + x) * s + s / 2, (8 + y) * s + s / 2);
                int hat  = img.getRGB((40 + x) * s + s / 2, (8 + y) * s + s / 2);
                face[y * 8 + x] = ((hat >>> 24) & 0xFF) > 127 ? hat : base; // как в TAB: непрозрачный второй слой рисуется поверх
            }
        }

        for (int[] ref : REFS) {
            int matched = 0;
            for (int i = 0; i < 64; i++) {
                int c = face[i], r = ref[i];
                if (Math.abs(((c >> 16) & 0xFF) - ((r >> 16) & 0xFF)) <= COLOR_TOLERANCE
                 && Math.abs(((c >> 8) & 0xFF) - ((r >> 8) & 0xFF)) <= COLOR_TOLERANCE
                 && Math.abs((c & 0xFF) - (r & 0xFF)) <= COLOR_TOLERANCE) matched++;
            }
            if (matched >= MIN_MATCHED) return true;
        }
        return false;
    }

    // ---- работа с GameProfile через рефлексию (в 1.21.9+ он стал record, имена методов менялись) ----

    private static String findTexturesValue(Object profile) {
        Object props = call(profile, "properties", "getProperties");
        if (props == null) return null;
        Object values = call(props, "values");
        if (values instanceof Iterable<?> it) {
            for (Object p : it) {
                if ("textures".equals(asString(call(p, "name", "getName")))) {
                    return asString(call(p, "value", "getValue"));
                }
            }
        }
        return null;
    }

    private static String extractSkinUrl(String base64Value) {
        try {
            String json = new String(Base64.getDecoder().decode(base64Value), StandardCharsets.UTF_8);
            JsonObject skin = JsonParser.parseString(json).getAsJsonObject()
                    .getAsJsonObject("textures").getAsJsonObject("SKIN");
            String url = skin.get("url").getAsString().replace("http://", "https://");
            // качаем только с серверов текстур Mojang
            return url.startsWith("https://textures.minecraft.net/") ? url : ABSENT;
        } catch (Throwable t) {
            return ABSENT;
        }
    }

    private static Object call(Object target, String... methodNames) {
        if (target == null) return null;
        for (String n : methodNames) {
            try {
                Method m = target.getClass().getMethod(n);
                return m.invoke(target);
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private static String asString(Object o) {
        return o instanceof String s ? s : null;
    }
}
