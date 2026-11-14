package dota.analysis;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Проверка карт лиги на невозможные данные (тест на противоречивых данных, docs/research/contradictory-data.md). */
class LeagueCheckTest {

    private static final Set<Integer> HEROES = IntStream.rangeClosed(1, 30).boxed().collect(Collectors.toSet());
    private static final long START = 1_790_000_000L;

    /** Нормальная карта: герои 1–10, игроки 101–110, драфт — баны 11–14 и пики. */
    private static League.Match valid() {
        List<League.Player> ps = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ps.add(new League.Player(101 + i, "p" + i, i + 1, i < 5, 1, 500, 5, 5, 5));
        }
        List<League.DraftStep> draft = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            draft.add(new League.DraftStep(i, false, i % 2 == 0, 11 + i));
        }
        for (int i = 0; i < 10; i++) {
            draft.add(new League.DraftStep(4 + i, true, i < 5, i + 1));
        }
        return new League.Match(1, START, 2400, true, 1, "A", 2, "B", ps, draft);
    }

    private static League.Match withPlayers(UnaryOperator<List<League.Player>> change) {
        League.Match m = valid();
        return new League.Match(m.matchId(), m.startTime(), m.durationSec(), m.radiantWin(), m.radiantTeamId(),
                m.radiantName(), m.direTeamId(), m.direName(), change.apply(new ArrayList<>(m.players())), m.draft());
    }

    private static League.Player hero(League.Player p, int hero) {
        return new League.Player(p.accountId(), p.personaName(), hero, p.radiant(), p.laneRole(), p.gpm(), p.kills(),
                p.deaths(), p.assists());
    }

    private static String problem(League.Match m) {
        return LeagueCheck.problem(m, HEROES, Set.of());
    }

    @Test
    void validMapPasses() {
        assertNull(problem(valid()));
    }

    @Test
    void duplicateIdRejected() {
        assertEquals("дубль match_id", LeagueCheck.problem(valid(), HEROES, Set.of(1L)));
    }

    @Test
    void impossibleMapsRejected() {
        League.Match m = valid();
        assertEquals("не 5 на 5", problem(withPlayers(ps -> ps.subList(0, 9))));
        assertEquals("неизвестный герой 999", problem(withPlayers(ps -> {
            ps.set(0, hero(ps.get(0), 999));
            return ps;
        })));
        assertEquals("герой дважды", problem(withPlayers(ps -> {
            ps.set(6, hero(ps.get(6), 1));
            return ps;
        })));
        assertEquals("игрок за обе стороны", problem(withPlayers(ps -> {
            League.Player p = ps.get(7);
            ps.set(7, new League.Player(101, p.personaName(), p.heroId(), false, 1, 500, 5, 5, 5));
            return ps;
        })));
        assertEquals("команда против самой себя", problem(new League.Match(1, START, 2400, true, 1, "A", 1, "A",
                m.players(), m.draft())));
        assertEquals("дата в будущем", problem(new League.Match(1, 4_000_000_000L, 2400, true, 1, "A", 2, "B",
                m.players(), m.draft())));
        assertEquals("длительность не больше нуля", problem(new League.Match(1, START, -1, true, 1, "A", 2, "B",
                m.players(), m.draft())));
        List<League.DraftStep> draft = new ArrayList<>(m.draft());
        draft.set(0, new League.DraftStep(0, false, true, 1)); // забанили героя, который в игре
        assertEquals("забаненный герой в игре", problem(new League.Match(1, START, 2400, true, 1, "A", 2, "B",
                m.players(), draft)));
    }
}
