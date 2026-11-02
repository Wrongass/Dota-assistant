package dota.analysis;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TeamLineupsTest {

    private static League.Player p(long id, int lane, int gpm) {
        return new League.Player(id, "", 1, true, lane, gpm, 1, 1, 1);
    }

    @Test
    void positionsFromLanesAndGold() {
        Map<Integer, Long> l = TeamLineups.positions(List.of(p(1, 1, 300), p(2, 1, 700), p(3, 2, 600), p(4, 3, 500), p(5, 3, 250)));
        assertEquals(Map.of(1, 2L, 2, 3L, 3, 4L, 4, 5L, 5, 1L), l);
        // лес или неизвестная линия — позиции не определить
        assertNull(TeamLineups.positions(List.of(p(1, 1, 300), p(2, 4, 700), p(3, 2, 600), p(4, 3, 500), p(5, 3, 250))));
        assertNull(TeamLineups.positions(List.of(p(1, 1, 300), p(2, 1, 700), p(3, 2, 600), p(4, 3, 500), p(0, 3, 250))));
    }

    @Test
    void unknownPlayerSkipsTheMap() {
        League.Match m = new League.Match(1, 100, 1800, true, 7, "A", 8, "B",
                List.of(p(1, 1, 300), p(2, 1, 700), p(3, 2, 600), p(4, 3, 500), p(5, 3, 250)), List.of());
        assertEquals(1, TeamLineups.seen(List.of(m), 7, Set.of(1L, 2L, 3L, 4L, 5L)).size());
        assertTrue(TeamLineups.seen(List.of(m), 7, Set.of(1L, 2L, 3L, 4L)).isEmpty(), "игрок не из конфига — карта не в счёт");
    }

    @Test
    void syberStarHasSeveralTournamentLineupsAndPicksShiftTheWeights() throws Exception {
        assumeTrue(Files.isDirectory(Path.of("data/raw/league")), "нет data/");
        AnalysisContext ctx = AnalysisContext.load(Path.of("config/players.yaml"), Path.of("data"));
        Squad syber = ctx.opponent("SyberStar");
        assumeTrue(syber != null, "SyberStar нет в конфиге");
        List<AnalysisContext.TeamLineup> lineups = ctx.lineupsOf(syber);
        assertTrue(lineups.size() >= 2, "у SyberStar больше одного состава в турнире");
        assertTrue(lineups.stream().anyMatch(l -> l.label().contains("Леонид Ильич")), "есть состав с заменой");

        DraftEngine engine = new DraftEngine(ctx);
        List<Integer> bans = List.of(23, 22, 1, 2, 31, 32, 33);
        DraftEngine.Advice start = engine.advise(new DraftEngine.State("SyberStar", true, bans));
        double first = start.theirLineups().get(0).weight();
        start.theirLineups().forEach(o -> assertEquals(first, o.weight(), 1e-9, "до пиков веса равные"));
        assertEquals(1.0, start.theirLineups().stream().mapToDouble(DraftEngine.LineupOdds::weight).sum(), 1e-9);
        // баны по позициям: мид — и SuperPoni, и Леонид Ильич
        List<String> mids = start.positionBans().stream().filter(pb -> pb.position() == 2).map(DraftEngine.PositionBans::player).toList();
        assertTrue(mids.contains("Леонид Ильич") && mids.contains("SuperPoni"), mids.toString());
        double midShare = start.positionBans().stream().filter(pb -> pb.position() == 2)
                .mapToDouble(DraftEngine.PositionBans::share).sum();
        assertEquals(1.0, midShare, 1e-9, "вероятности игроков на позиции в сумме 1");

        // выбран состав — только он, и строки по позициям — по одному игроку
        String key = lineups.stream().filter(l -> l.label().contains("Леонид Ильич")).findFirst().orElseThrow().key();
        DraftEngine.Advice chosen = engine.advise(new DraftEngine.State("SyberStar", true, bans, Map.of(), key));
        assertEquals(key, chosen.theirLineup());
        assertEquals(5, chosen.positionBans().size());

        // капитан отдал их героя Леониду Ильичу — составы без него отпадают
        List<Integer> withPick = new java.util.ArrayList<>(bans);
        withPick.add(42);
        withPick.add(25);
        DraftEngine.Advice fixed = engine.advise(new DraftEngine.State("SyberStar", true, withPick, Map.of(25, "Леонид Ильич"), null));
        fixed.theirLineups().forEach(o -> assertEquals(o.label().contains("Леонид Ильич"), o.weight() > 0, o.toString()));
        assertTrue(fixed.micros() < 5_000_000, "совет по всем составам быстрее 5 с: " + fixed.micros());
    }
}
