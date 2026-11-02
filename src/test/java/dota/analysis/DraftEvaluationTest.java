package dota.analysis;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DraftEvaluationTest {

    private static AnalysisContext ctx() throws Exception {
        assumeTrue(Files.isDirectory(Path.of("data/raw")), "нет data/");
        return AnalysisContext.load(Path.of("config/players.yaml"), Path.of("data"));
    }

    @Test
    void weightsSumToOne() {
        assertEquals(1.0, Scoring.DRAFT_WEIGHTS.values().stream().mapToDouble(Double::doubleValue).sum(), 1e-9);
    }

    @Test
    void everySectionIsComputedAndMirrorFlipsTheSign() throws Exception {
        DraftEvaluation e = new DraftEvaluation(ctx());
        // Phantom Lancer, Puck, Tidehunter, Earthshaker, Crystal Maiden против Sniper, Lina, Mars, Lion, Witch Doctor
        Map<Integer, Integer> a = Map.of(12, 1, 13, 2, 29, 3, 7, 4, 5, 5);
        Map<Integer, Integer> b = Map.of(35, 1, 25, 2, 129, 3, 26, 4, 30, 5);
        DraftEvaluation.Result r = e.evaluate(a, b, List.of(), true);
        assertEquals(3, r.lanes().size());
        assertEquals(List.of("game", "mid", "late", "teamfight", "split", "kite"),
                r.sections().stream().map(DraftEvaluation.Section::key).toList());
        r.sections().forEach(s -> assertTrue(Math.abs(s.score()) <= 1 && !s.reasons().isEmpty(), s.toString()));
        DraftEvaluation.Result mirror = e.evaluate(b, a, List.of(), true);
        assertEquals(r.total().score(), -mirror.total().score(), 0.05, "оценка симметрична");
    }

    @Test
    void illusionHeroWithoutAnswerIsReported() throws Exception {
        DraftEvaluation e = new DraftEvaluation(ctx());
        // у них Phantom Lancer, у нас ни одного героя с AoE против иллюзий (Lion, Pudge, Bane, Slardar, Spirit Breaker)
        Map<Integer, Integer> ours = Map.of(71, 1, 26, 2, 28, 3, 14, 4, 3, 5);
        Map<Integer, Integer> theirs = Map.of(12, 1, 13, 2, 29, 3, 7, 4, 5, 5);
        DraftEvaluation.Section game = e.evaluate(ours, theirs, List.of(), true).sections().get(0);
        assertTrue(game.reasons().stream().anyMatch(s -> s.contains("Phantom Lancer") && s.contains("AoE")), game.reasons().toString());
    }

    @Test
    void positionsChangeTheLanes() throws Exception {
        DraftEvaluation e = new DraftEvaluation(ctx());
        Map<Integer, Integer> theirs = Map.of(35, 1, 25, 2, 129, 3, 26, 4, 30, 5);
        var asIs = e.evaluate(Map.of(12, 1, 13, 2, 29, 3, 7, 4, 5, 5), theirs, List.of(), true);
        var swapped = e.evaluate(Map.of(12, 2, 13, 1, 29, 3, 7, 4, 5, 5), theirs, List.of(), true);
        assertNotEquals(asIs.lanes().get(1).reasons().get(0), swapped.lanes().get(1).reasons().get(0), "в миде другой герой");
    }

    @Test
    void outpicksCountCountersTakenLater() throws Exception {
        DraftEvaluation e = new DraftEvaluation(ctx());
        int[] none = e.outpicks(List.of(), true);
        assertArrayEquals(new int[]{0, 0}, none);
    }
}
