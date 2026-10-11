package dota.fetch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class RawFetcherTest {

    @TempDir
    Path dir;

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void acceptsNormalJson() throws Exception {
        RawFetcher.checkJson(b("[{\"match_id\":1}]"));
        RawFetcher.checkJson(b("{\"profile\":{},\"rank_tier\":65}"));
    }

    @Test
    void rejectsErrorBodies() {
        assertThrows(IOException.class, () -> RawFetcher.checkJson(b("{\"error\":\"Not Found\"}")));
        assertThrows(IOException.class, () -> RawFetcher.checkJson(b("{\"err\":\"Query read timeout\"}")));
        assertThrows(IOException.class, () -> RawFetcher.checkJson(b("")));
    }

    @Test
    void writesAtomicallyAndCreatesDirs() throws Exception {
        Path file = dir.resolve("raw/our/1/profile.json");
        RawFetcher.writeAtomically(file, b("{}"));
        assertEquals("{}", Files.readString(file));
        assertFalse(Files.exists(file.resolveSibling("profile.json.tmp")));
    }

    @Test
    void skipsExistingFileWithoutRefresh() throws Exception {
        Path file = dir.resolve("x.json");
        Files.writeString(file, "old");
        // клиент не должен вызываться: адрес заведомо недоступен
        RawFetcher fetcher = new RawFetcher(new OpenDotaClient("http://127.0.0.1:1", null, Duration.ZERO), false);
        fetcher.fetch(new Endpoints.Target("/x", file));
        assertEquals(1, fetcher.skipped());
        assertEquals("old", Files.readString(file));
    }

    @Test
    void matchesQueryHasSignificantZeroAndProjectFields() {
        String q = Endpoints.matchesQuery();
        assertTrue(q.startsWith("significant=0&"));
        assertTrue(q.contains("project=lane_role"));
        assertEquals(Endpoints.MATCH_FIELDS.size(), q.split("project=").length - 1);
    }
}
