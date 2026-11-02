package dota.analysis;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DraftPairsTest {

    @Test
    void laneDuoOnlyForPlayersOnTheSameLane() {
        assertEquals("вместе на лёгкой линии", DraftEngine.laneDuo(5, 1));
        assertEquals("вместе на сложной линии", DraftEngine.laneDuo(3, 4));
        assertNull(DraftEngine.laneDuo(2, 3));
    }

    @Test
    void doublePickGivesThreeDifferentPairsWithReasonsAndSkipsNeverPick() throws Exception {
        assumeTrue(Files.isDirectory(Path.of("data/raw")), "нет data/");
        AnalysisContext ctx = AnalysisContext.load(Path.of("config/players.yaml"), Path.of("data"));
        DraftEngine engine = new DraftEngine(ctx);
        // мы первые: 7 банов, наш пик, их пик, 3 бана, их пик — дальше наш двойной пик
        List<Integer> heroes = List.of(23, 22, 1, 2, 31, 32, 33, 42, 48, 3, 4, 5, 49);
        DraftEngine.Advice a = engine.advise(new DraftEngine.State("SyberStar", true, heroes, Map.of(), null, Set.of()));
        assertFalse(a.pairPicks().isEmpty());
        Set<Integer> seen = new HashSet<>();
        for (DraftEngine.PairPick p : a.pairPicks()) {
            assertTrue(seen.add(p.a()) && seen.add(p.b()), "герой не повторяется в парах: " + a.pairPicks());
            assertNotNull(p.note());
            assertFalse(p.note().isBlank());
        }
        // Invoker в never_pick конфига, а отмеченный на сайте герой тоже уходит из советов
        int invoker = 74;
        int first = a.pickSuggestions().get(0).heroId();
        assertTrue(a.pickSuggestions().stream().noneMatch(r -> r.heroId() == invoker));
        DraftEngine.Advice b = engine.advise(new DraftEngine.State("SyberStar", true, heroes, Map.of(), null, Set.of(first)));
        assertTrue(b.pickSuggestions().stream().noneMatch(r -> r.heroId() == first));
        assertTrue(b.pairPicks().stream().noneMatch(p -> p.a() == first || p.b() == first));
        assertTrue(b.laneOptions().stream().flatMap(o -> o.heroes().stream()).noneMatch(r -> r.heroId() == first));
    }
}
