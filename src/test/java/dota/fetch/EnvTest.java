package dota.fetch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class EnvTest {

    @TempDir
    Path dir;

    @Test
    void readsNameFromFileSkippingCommentsAndBlanks() throws Exception {
        Path file = Files.writeString(dir.resolve("tokens.env"), """
                # комментарий STRATZ_TOKEN=нет
                OTHER=1
                STRATZ_TOKEN= abc.def
                EMPTY=
                """);
        assertEquals("abc.def", Env.fromFile(file, "STRATZ_TOKEN"));
        assertNull(Env.fromFile(file, "EMPTY"));
        assertNull(Env.fromFile(file, "MISSING"));
        assertNull(Env.fromFile(dir.resolve("none.env"), "STRATZ_TOKEN"));
    }

    @Test
    void committedTokensFileHasStratzToken() {
        assertNotNull(Env.fromFile(Path.of("config/tokens.env"), "STRATZ_TOKEN"),
                "config/tokens.env: выгрузка линии на сервере работает без ручной настройки");
    }
}
