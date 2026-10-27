package dota.fetch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Переменная окружения, а если её нет — строка {@code NAME=value} из файла .env в текущей папке (он не в git). */
final class Env {

    private Env() {
    }

    static String get(String name) {
        String value = System.getenv(name);
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        Path file = Path.of(".env");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(file)) {
                String s = line.trim();
                if (s.startsWith(name + "=")) {
                    String v = s.substring(name.length() + 1).trim();
                    return v.isEmpty() ? null : v;
                }
            }
        } catch (IOException e) {
            Log.error(".env: %s", e.getMessage());
        }
        return null;
    }
}
