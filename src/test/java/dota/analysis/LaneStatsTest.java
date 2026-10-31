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
    void lanesFaceTheRightPositions() {
        assertEquals(List.of(3, 4), LaneStats.opposingPositions(1));
        assertEquals(List.of(2), LaneStats.opposingPositions(2));
        assertEquals(List.of(1, 5), LaneStats.opposingPositions(4));
        assertEquals(List.of(3, 4), LaneStats.opposingPositions(5));
    }
}
