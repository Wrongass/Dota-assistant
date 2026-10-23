package dota.fetch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Официальные картинки Valve для сайта: портреты и иконки героев (пути — из constants/heroes.json, CDN Steam,
 * те же, что у OpenDota и Dotabuff) и значки рангов (с OpenDota). Кладутся в data/img и отдаются сайтом сами,
 * чтобы во время игры не зависеть от интернета. Уже скачанные файлы не перекачиваются: картинки не меняются.
 * Ошибки здесь не валят выгрузку данных — сайт без картинки покажет инициалы героя.
 */
final class ImageFetcher {

    static final String STEAM_CDN = "https://cdn.cloudflare.steamstatic.com";
    static final String RANK_ICONS = "https://www.opendota.com/assets/images/dota2/rank_icons/";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G'};

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private int downloaded, skipped, failed;

    /** Что качать: url → файл. heroes.json — справочник героев OpenDota (объект с ключами-id). */
    static Map<String, Path> targets(JsonNode heroes, Path imgDir) {
        Map<String, Path> result = new java.util.LinkedHashMap<>();
        heroes.properties().forEach(e -> {
            JsonNode h = e.getValue();
            String id = h.path("id").asText(e.getKey());
            addIfPresent(result, h.path("img").asText(""), imgDir.resolve("heroes").resolve(id + ".png"));
            addIfPresent(result, h.path("icon").asText(""), imgDir.resolve("icons").resolve(id + ".png"));
        });
        for (int tier = 0; tier <= 8; tier++) {
            result.put(RANK_ICONS + "rank_icon_" + tier + ".png", imgDir.resolve("ranks").resolve("rank_icon_" + tier + ".png"));
        }
        for (int star = 1; star <= 5; star++) {
            result.put(RANK_ICONS + "rank_star_" + star + ".png", imgDir.resolve("ranks").resolve("rank_star_" + star + ".png"));
        }
        return result;
    }

    private static void addIfPresent(Map<String, Path> result, String path, Path file) {
        if (!path.isBlank()) {
            result.put(STEAM_CDN + path.replaceAll("\\?$", ""), file);
        }
    }

    void fetch(Path heroesJson, Path imgDir) {
        JsonNode heroes;
        try {
            heroes = new ObjectMapper().readTree(heroesJson.toFile());
        } catch (IOException e) {
            Log.error("images: cannot read %s: %s", heroesJson, e.getMessage());
            return;
        }
        List<String> errors = new ArrayList<>();
        for (var t : targets(heroes, imgDir).entrySet()) {
            if (Files.exists(t.getValue())) {
                skipped++;
                continue;
            }
            try {
                HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(t.getKey()))
                        .timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofByteArray());
                if (r.statusCode() != 200 || !isPng(r.body())) {
                    throw new IOException("HTTP " + r.statusCode());
                }
                RawFetcher.writeAtomically(t.getValue(), r.body());
                downloaded++;
            } catch (IOException e) {
                failed++;
                errors.add(t.getKey() + ": " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        errors.stream().limit(5).forEach(e -> Log.error("image %s", e));
        Log.info("images: downloaded %d, skipped %d, failed %d -> %s", downloaded, skipped, failed, imgDir);
    }

    static boolean isPng(byte[] body) {
        if (body == null || body.length < PNG.length) {
            return false;
        }
        for (int i = 0; i < PNG.length; i++) {
            if (body[i] != PNG[i]) {
                return false;
            }
        }
        return true;
    }
}
