package dota.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LaneStatsTest {

    @TempDir
    Path data;

    /** Герой 47 (Viper) на миде против 25 (Lina) и 106 (Ember) за две недели, плюс матчап по игре. */
    private void write(String week) throws Exception {
        Path dir = data.resolve("raw/stratz/lanes").resolve(week);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("47.json"), """
                {"data": {"heroStats": {
                  "w2963p2": [
                    {"heroId2": 25, "matchCount": 100, "winCount": 30, "drawCount": 30, "lossCount": 30,
                     "stompWinCount": 5, "stompLossCount": 5, "matchWinCount": 50},
                    {"heroId2": 106, "matchCount": 20, "winCount": 15, "drawCount": 2, "lossCount": 3,
                     "stompWinCount": 0, "stompLossCount": 0, "matchWinCount": 12}],
                  "w2964p2": [
                    {"heroId2": 106, "matchCount": 40, "winCount": 25, "drawCount": 5, "lossCount": 2,
                     "stompWinCount": 6, "stompLossCount": 2, "matchWinCount": 24}],
                  "w2964p1": [
                    {"heroId2": 25, "matchCount": 500, "winCount": 100, "drawCount": 100, "lossCount": 300,
                     "stompWinCount": 0, "stompLossCount": 0, "matchWinCount": 200}],
                  "mu": {"advantage": [{"heroId": 47, "matchCountVs": 1000, "vs": [
                    {"heroId2": 25, "matchCount": 500, "winCount": 250, "synergy": 1.5},
                    {"heroId2": 106, "matchCount": 50, "winCount": 30, "synergy": 6.0}]}]}
                }}}
                """);
    }

    @Test
    void sumsWeeksFoldsStompsAndKeepsPositionsApart() throws Exception {
        write("2964");
        LaneStats s = LaneStats.load(data);
        assertFalse(s.isEmpty());

        LaneStats.Cell ember = s.cell(47, 2, 106);
        assertEquals(60, ember.matches());
        assertEquals(15 + 25 + 6, ember.win());
        assertEquals(3 + 2 + 2, ember.loss());
        assertEquals(7, ember.draw());
        assertEquals(36, ember.gameWins());
        assertEquals(100, s.cell(47, 2, 25).matches(), "позиция 1 не смешивается с мидом");
        assertEquals(160, s.lanesAt(47, 2));
    }

    @Test
    void laneOutcomeIsShrunkToHeroUsualResult() throws Exception {
        write("2964");
        LaneStats s = LaneStats.load(data);
        LaneStats.Outcome o = s.lane(47, 2, List.of(106));
        double raw = (46 - 7) / 60.0;
        double base = s.baseAdvantage(47, 2);
        assertEquals((46 - 7 + LaneStats.PRIOR * base) / (60 + LaneStats.PRIOR), o.advantage(), 1e-9);
        assertTrue(o.advantage() < raw && o.advantage() > base, "сглаженное между обычным и сырым");
        assertEquals(46 / 60.0, o.win(), 1e-9);
    }

    @Test
    void thinPairsAndUnknownHeroesGiveNoAnswer() throws Exception {
        write("2964");
        LaneStats s = LaneStats.load(data);
        assertNull(s.lane(47, 3, List.of(106)), "на позиции 3 данных нет");
        assertNull(s.lane(1, 2, List.of(106)));
        assertTrue(Double.isNaN(s.gameAdvantage(47, List.of(106))), "50 игр меньше порога по игре");
        assertEquals(0.015, s.gameAdvantage(47, List.of(25)), 1e-9);
    }

    @Test
    void readsLatestWeekAndWorksWithoutData() throws Exception {
        assertTrue(LaneStats.load(data).isEmpty());
        write("2963");
        write("2964");
        assertEquals("2964", LaneStats.load(data).week);
    }

    @Test
    void matchupsOfSeveralWeeksAreWeightedByGamesWithAlliesAndPositions() throws Exception {
        Path dir = data.resolve("raw/stratz/lanes/2969");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("_sample.json"), """
                {"fetched_at": "2026-11-27T10:00:00Z", "patch": "7.42", "patch_only": true, "weeks": [2968, 2969],
                 "week_starts": ["2026-11-19T00:00:00Z", "2026-11-26T00:00:00Z"]}
                """);
        Files.writeString(dir.resolve("47.json"), """
                {"data": {"heroStats": {
                  "w2968p2": [{"heroId2": 25, "matchCount": 50, "winCount": 20, "drawCount": 10, "lossCount": 20,
                               "stompWinCount": 0, "stompLossCount": 0, "matchWinCount": 25}],
                  "mu2968": {"advantage": [{"heroId": 47, "vs": [{"heroId2": 25, "matchCount": 300, "winCount": 150, "synergy": 1.0}],
                                            "with": [{"heroId2": 5, "matchCount": 80, "winCount": 50, "synergy": 4.0}]}]},
                  "mu2969": {"advantage": [{"heroId": 47, "vs": [{"heroId2": 25, "matchCount": 100, "winCount": 60, "synergy": 5.0}],
                                            "with": [{"heroId2": 5, "matchCount": 40, "winCount": 20, "synergy": -2.0}]}]},
                  "s2968": [{"position": "POSITION_2", "matchCount": 900, "winCount": 450}],
                  "s2969": [{"position": "POSITION_2", "matchCount": 100, "winCount": 60},
                            {"position": "POSITION_3", "matchCount": 10, "winCount": 4}],
                  "i2968p2": [{"itemId": 1, "instance": 0, "time": 20, "matchCount": 600, "winCount": 300}]
                }}}
                """);
        LaneStats s = LaneStats.load(data);
        assertTrue(s.patchOnly);
        assertEquals("7.42", s.patch);
        assertTrue(s.sample.contains("19.11 — 27.11") && s.sample.contains("патч 7.42"), s.sample);
        assertEquals((300 * 1.0 + 100 * 5.0) / 400 / 100, s.gameAdvantage(47, List.of(25)), 1e-9, "среднее по играм");
        assertEquals(400, s.gameMatches(47, List.of(25)));
        assertEquals((80 * 4.0 - 40 * 2.0) / 120 / 100, s.allyAdvantage(47, 5), 1e-9);
        assertEquals(120, s.allyGames(47, 5));
        assertArrayEquals(new long[]{1000, 510}, s.positionGames(47, 2));
        assertArrayEquals(new long[]{0, 0}, s.positionGames(1, 2));
        assertFalse(s.items.isEmpty());
    }

    @Test
    void lanesFaceTheRightPositions() {
        assertEquals(List.of(3, 4), LaneStats.opposingPositions(1));
        assertEquals(List.of(2), LaneStats.opposingPositions(2));
        assertEquals(List.of(1, 5), LaneStats.opposingPositions(4));
        assertEquals(List.of(3, 4), LaneStats.opposingPositions(5));
    }
}
