package dota.fetch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class MetaEndpointsTest {

    @TempDir
    Path dir;

    @Test
    void currentPatchIsLastEntry() throws Exception {
        Path file = dir.resolve("patch.json");
        Files.writeString(file, """
                [{"name":"7.40","date":"2025-12-16T00:50:40.281Z","id":59},
                 {"name":"7.41","date":"2026-03-24T00:50:59.580Z","id":60}]""");
        MetaEndpoints.Patch patch = MetaEndpoints.currentPatch(file);
        assertEquals("7.41", patch.name());
        assertEquals(Instant.parse("2026-03-24T00:50:59.580Z"), patch.start());
    }

    @Test
    void sampleWhereUsesWindowRankAndPatch() {
        var s = new MetaEndpoints.Sample(9_000_000_000L, 1_000_000, 50, 1774313459);
        assertEquals("match_id > 8999000000 and avg_rank_tier >= 50 and start_time >= 1774313459", s.where());
        assertTrue(MetaEndpoints.synergySql(s).contains(s.where()));
        assertTrue(MetaEndpoints.countersSql(s).contains("unnest(enemy)"));
    }

    @Test
    void parsesMaxMatchIdFromExplorerResponse() throws Exception {
        byte[] body = "{\"command\":\"SELECT\",\"rowCount\":1,\"rows\":[{\"max_match_id\":9037871805}]}"
                .getBytes(StandardCharsets.UTF_8);
        assertEquals(9037871805L, MetaEndpoints.parseMaxMatchId(body));
    }

    @Test
    void explorerUrlIsEncoded() {
        var s = new MetaEndpoints.Sample(100, 10, 50, 0);
        String url = MetaEndpoints.explorerTargets(dir, s, 1).get(0).url();
        assertTrue(url.startsWith("/explorer?sql=with+pm+as"));
        assertFalse(url.contains(" "));
    }
}
