package dota.fetch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Переменная окружения, а если её нет — строка {@code NAME=value} из файла .env в текущей папке (он не в git), а затем
 * из {@code config/tokens.env} (в git, чтобы выгрузка работала без ручной настройки; в Docker папка config подключена).
 */
final class Env {

    private Env() {
    }

    static String get(String name) {
        String value = System.getenv(name);
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        for (Path file : FILES) {
            String fromFile = fromFile(file, name);
            if (fromFile != null) {
                return fromFile;
            }
        }
        return null;
    }

    static final List<Path> FILES = List.of(Path.of(".env"), Path.of("config/tokens.env"));

    static String fromFile(Path file, String name) {
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
            Log.error("%s: %s", file, e.getMessage());
        }
        return null;
    }
}
