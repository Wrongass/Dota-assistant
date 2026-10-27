package dota.fetch;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StratzFetcherTest {

    @Test
    void weekNumbersMatchStratz() {
        // STRATZ вернул week = 2964 для недели, начавшейся 2026-10-22 00:00 UTC
        assertEquals(2964, StratzFetcher.weekOf(Instant.parse("2026-10-27T18:00:00Z")));
        assertEquals(List.of(2961L, 2962L, 2963L, 2964L), StratzFetcher.weeks(2964));
    }

    @Test
    void queryAsksEveryPositionOfEveryWeekAndMatchup() {
        String q = StratzFetcher.query(47, List.of(2963L, 2964L));
        for (String alias : List.of("w2963p1:", "w2963p5:", "w2964p2:", "mu:")) {
            assertTrue(q.contains(alias), alias);
        }
        assertTrue(q.contains("week: " + 2964 * StratzFetcher.WEEK_SECONDS));
        assertTrue(q.contains("bracketBasicIds: [LEGEND_ANCIENT,DIVINE_IMMORTAL]"));
        assertEquals(11, q.split("heroId: 47").length - 1);
    }

    @Test
    void graphqlErrorsAreNotSaved() {
        assertThrows(Exception.class, () -> StratzFetcher.checkGraphql(
                "{\"errors\":[{\"message\":\"bad\"}],\"data\":null}".getBytes(StandardCharsets.UTF_8)));
        assertDoesNotThrow(() -> StratzFetcher.checkGraphql(
                "{\"data\":{\"heroStats\":{}}}".getBytes(StandardCharsets.UTF_8)));
    }
}
