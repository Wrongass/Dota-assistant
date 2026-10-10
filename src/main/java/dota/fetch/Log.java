package dota.fetch;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Минимальный логгер в stdout: время + сообщение. */
final class Log {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private Log() {
    }

    static void info(String format, Object... args) {
        System.out.println(LocalTime.now().format(TIME) + " " + String.format(format, args));
    }

    static void error(String format, Object... args) {
        System.err.println(LocalTime.now().format(TIME) + " ERROR " + String.format(format, args));
    }
}
