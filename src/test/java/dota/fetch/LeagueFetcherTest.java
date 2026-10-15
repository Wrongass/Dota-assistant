package dota.fetch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LeagueFetcherTest {

    @TempDir
    Path dir;

    @Test
    void idsAreSortedNewestFirst() throws Exception {
        assertEquals(List.of(30L, 20L, 10L),
                LeagueFetcher.parseIds("[20,10,30]".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void readsStartTime() throws Exception {
        assertEquals(1791642699L, LeagueFetcher.startTime(
                "{\"match_id\":1,\"start_time\":1791642699}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void failedIdListIsRecordedNotThrown() throws Exception {
        // недоступный адрес: список id не скачан — ошибка учитывается, матчи не трогаются
        OpenDotaClient client = new OpenDotaClient("http://127.0.0.1:1", null, Duration.ZERO) {
            @Override
            public byte[] get(String pathAndQuery) throws java.io.IOException {
                throw new java.io.IOException("offline");
            }
        };
        RawFetcher fetcher = new RawFetcher(client, false);
        new LeagueFetcher(client, fetcher, false).fetch(1, LocalDate.of(2026, 10, 1), dir);
        assertEquals(1, fetcher.failed());
        assertFalse(Files.exists(dir.resolve("matches")));
    }
}
