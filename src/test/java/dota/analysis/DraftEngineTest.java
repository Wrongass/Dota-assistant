package dota.analysis;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DraftEngineTest {

    @Test
    void captainsModeOrderHasSevenBansAndFivePicksPerTeam() {
        assertEquals(24, DraftEngine.SLOTS.size());
        for (boolean first : new boolean[]{true, false}) {
            long bans = DraftEngine.SLOTS.stream().filter(s -> s.firstTeam() == first && !s.pick()).count();
            long picks = DraftEngine.SLOTS.stream().filter(s -> s.firstTeam() == first && s.pick()).count();
            assertEquals(7, bans);
            assertEquals(5, picks);
        }
        assertTrue(DraftEngine.SLOTS.get(7).pick() && DraftEngine.SLOTS.get(7).firstTeam(), "первый пик — у первой команды");
    }

    @Test
    void assignmentProbabilitiesSumToOneAndFollowWeights() {
        // герой 0 почти наверняка у игрока 1, герой 1 — у игрока 0
        double[][] w = {{0.01, 10, 0.01}, {10, 0.01, 0.01}};
        double[][] p = DraftEngine.assignmentProbabilities(w);
        for (double[] row : p) {
            assertEquals(1.0, row[0] + row[1] + row[2], 1e-9);
        }
        assertTrue(p[0][1] > 0.99);
        assertTrue(p[1][0] > 0.99);
    }

    @Test
    void oneHeroCannotGoToTwoPlayersAtOnce() {
        // оба героя «хотят» игрока 0 — вместе они его не получат
        double[][] w = {{10, 1}, {10, 1}};
        double[][] p = DraftEngine.assignmentProbabilities(w);
        assertEquals(1.0, p[0][0] + p[1][0], 1e-9, "игрок 0 занят ровно одним героем");
    }

    @Test
    void adviceOnRealDataIsFastAndSkipsUsedHeroes() throws Exception {
        assumeTrue(Files.isDirectory(Path.of("data/raw")), "нет data/");
        AnalysisContext ctx = AnalysisContext.load(Path.of("config/players.yaml"), Path.of("data"));
        DraftEngine engine = new DraftEngine(ctx);
        // 7 банов + первый пик (наш: Wraith King = 42) + их пик (Lina = 25)
        List<Integer> heroes = new ArrayList<>(List.of(23, 22, 1, 2, 31, 32, 33, 42, 25));
        DraftEngine.Advice a = engine.advise(new DraftEngine.State("UPPO_TEAM", true, heroes));

        assertEquals(9, a.step());
        assertTrue(a.oursNext(), "ход 10 по порядку CM — b1, бан первой команды (наш)");
        assertFalse(a.pickNext());
        assertEquals(List.of(42), a.ourPicks());
        assertEquals(List.of(25), a.theirPicks());
        assertEquals(1, a.theirAssignment().size());
        Set<Integer> used = new HashSet<>(heroes);
        a.theirNextPicks().forEach(r -> assertFalse(used.contains(r.heroId()), r.name()));
        a.banSuggestions().forEach(r -> assertFalse(used.contains(r.heroId()), r.name()));
        a.pickSuggestions().forEach(r -> assertFalse(used.contains(r.heroId()), r.name()));
        assertFalse(a.theirNextPicks().isEmpty());
        assertFalse(a.pickSuggestions().isEmpty());
        assertTrue(a.micros() < 1_000_000, "совет дольше секунды: " + a.micros() + " мкс");
    }

    @Test
    void backtestAssignsMostHeroesToTheRightPlayers() throws Exception {
        assumeTrue(Files.isDirectory(Path.of("data/raw/league")), "нет матчей лиги");
        AnalysisContext ctx = AnalysisContext.load(Path.of("config/players.yaml"), Path.of("data"));
        DraftBacktest.Result r = DraftBacktest.run(ctx, false);
        assumeTrue(r.assigned() > 0, "нет матчей между известными командами");
        assertTrue(r.assignedRight() >= 0.8 * r.assigned(), r.toString());
    }
}
