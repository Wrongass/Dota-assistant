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
        for (String alias : List.of("w2963p1:", "w2963p5:", "w2964p2:", "i2963p1:", "i2964p5:", "mu2963:", "mu2964:",
                "s2964:", "with {")) {
            assertTrue(q.contains(alias), alias);
        }
        assertTrue(q.contains("week: " + 2964 * StratzFetcher.WEEK_SECONDS));
        assertTrue(q.contains("bracketBasicIds: [LEGEND_ANCIENT,DIVINE_IMMORTAL]"));
        // на неделю: 5 линий, 5 предметов, 1 матчап
        assertEquals(22, q.split("heroId: 47").length - 1);
    }

    @Test
    void onlyWeeksThatStartedAfterThePatch() {
        // 7.42 вышел 2026-11-18 21:28, неделя 2968 началась 2026-11-19 00:00
        Instant patch = Instant.parse("2026-11-18T21:28:07Z");
        assertEquals(List.of(2968L), StratzFetcher.patchWeeks(2968, patch));
        assertEquals(List.of(2968L, 2969L), StratzFetcher.patchWeeks(2969, patch));
        assertEquals(List.of(2969L, 2970L, 2971L, 2972L), StratzFetcher.patchWeeks(2972, patch), "не больше 4 недель");
        // патч вышел в ходе недели 2967: целой недели нового патча нет — прежние 4 недели
        assertEquals(StratzFetcher.weeks(2967), StratzFetcher.patchWeeks(2967, patch));
        assertEquals(StratzFetcher.weeks(2967), StratzFetcher.patchWeeks(2967, null));
    }

    @Test
    void runningWeekIsFetchedAgainAfterADay() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var sample = json.readTree("{\"fetched_at\": \"2026-11-19T08:00:00Z\", \"weeks\": [2968]}");
        List<Long> weeks = List.of(2968L);
        assertFalse(StratzFetcher.stale(sample, weeks, Instant.parse("2026-11-19T20:00:00Z")), "12 часов — ещё свежо");
        assertTrue(StratzFetcher.stale(sample, weeks, Instant.parse("2026-11-20T08:00:00Z")));
        assertTrue(StratzFetcher.stale(sample, List.of(2965L, 2966L, 2967L, 2968L), Instant.parse("2026-11-19T09:00:00Z")),
                "другие недели");
        var done = json.readTree("{\"fetched_at\": \"2026-11-26T01:00:00Z\", \"weeks\": [2968]}");
        assertFalse(StratzFetcher.stale(done, weeks, Instant.parse("2026-11-30T08:00:00Z")), "неделя закончилась до скачивания");
    }

    @Test
    void graphqlErrorsAreNotSaved() {
        assertThrows(Exception.class, () -> StratzFetcher.checkGraphql(
                "{\"errors\":[{\"message\":\"bad\"}],\"data\":null}".getBytes(StandardCharsets.UTF_8)));
        assertDoesNotThrow(() -> StratzFetcher.checkGraphql(
                "{\"data\":{\"heroStats\":{}}}".getBytes(StandardCharsets.UTF_8)));
    }
}
