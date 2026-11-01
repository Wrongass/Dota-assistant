package dota.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Общий драфт по ссылке (FEAT-14): состояние драфта на сервере под коротким кодом, его видят и меняют все, у кого ссылка
 * (и пароль сайта). Файл {@code state/drafts/{код}.json}: соперник, карта, первый ход, ходы, ручные назначения,
 * версия (растёт на каждом сохранении) и id вкладки, которая сохранила последней. Последняя запись побеждает.
 */
final class SharedDrafts {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern CODE = Pattern.compile("[a-z0-9]{8}");
    private static final String ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path dir;

    /** dir — null, если сайт запущен без папки состояния: общий драфт недоступен. */
    SharedDrafts(Path dir) {
        this.dir = dir;
    }

    boolean enabled() {
        return dir != null;
    }

    static boolean validCode(String code) {
        return code != null && CODE.matcher(code).matches();
    }

    static String newCode() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            b.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return b.toString();
    }

    synchronized Optional<JsonNode> read(String code) throws IOException {
        if (!enabled() || !validCode(code)) {
            return Optional.empty();
        }
        Path file = dir.resolve(code + ".json");
        return Files.isRegularFile(file) ? Optional.of(JSON.readTree(file.toFile())) : Optional.empty();
    }

    /**
     * Сохранить драфт: только известные поля, с проверкой размеров; версия = прошлая + 1. Возвращает сохранённое.
     * Неверные данные — IllegalArgumentException.
     */
    synchronized JsonNode save(String code, JsonNode body) throws IOException {
        if (!enabled()) {
            throw new IllegalStateException("общий драфт недоступен: сайт запущен без папки состояния");
        }
        if (!validCode(code)) {
            throw new IllegalArgumentException("неверный код");
        }
        ObjectNode clean = JSON.createObjectNode();
        String opponent = body.path("opponent").asText("");
        if (opponent.isEmpty() || opponent.length() > 80) {
            throw new IllegalArgumentException("нет соперника");
        }
        clean.put("opponent", opponent);
        int map = body.path("map").asInt(1);
        clean.put("map", map >= 1 && map <= 3 ? map : 1);
        clean.put("weFirst", body.path("weFirst").asBoolean(true));
        var heroes = clean.putArray("heroes");
        JsonNode in = body.path("heroes");
        if (!in.isArray() || in.size() > 24) {
            throw new IllegalArgumentException("ходов должно быть не больше 24");
        }
        in.forEach(h -> heroes.add(Math.max(0, Math.min(1000, h.asInt(0)))));
        ObjectNode assign = clean.putObject("assign");
        body.path("assign").fields().forEachRemaining(e -> {
            if (e.getKey().matches("\\d{1,4}") && e.getValue().isTextual() && e.getValue().asText().length() <= 60
                    && assign.size() < 10) {
                assign.put(e.getKey(), e.getValue().asText());
            }
        });
        String client = body.path("client").asText("");
        clean.put("client", client.length() <= 40 ? client : "");
        long version = read(code).map(n -> n.path("version").asLong(0)).orElse(0L) + 1;
        clean.put("version", version);
        Files.createDirectories(dir);
        Path tmp = dir.resolve(code + ".json.tmp");
        JSON.writeValue(tmp.toFile(), clean);
        Files.move(tmp, dir.resolve(code + ".json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return clean;
    }
}
