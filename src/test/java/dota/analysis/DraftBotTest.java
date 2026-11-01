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

class DraftBotTest {

    @Test
    void contestedHeroIsStolenFirstAndUncontestedWaits() {
        // у них играют DK и Techies; DK нужен и нам (наш следующий пик — DK с шансом 35%, до их пика у нас 2 пика),
        // на Techies у нас никто не играет. Techies для них чуть ценнее, но может подождать.
        double dk = DraftBot.pickScore(0.80, DraftBot.urgency(0.35, 2), 0.6);
        double techies = DraftBot.pickScore(0.85, DraftBot.urgency(0.0, 2), 0.0);
        assertTrue(dk > techies, "первым пиком крадут DK: " + dk + " vs " + techies);
        // если до их следующего пика у нас пиков нет, срочности нет — берут более ценного
        assertTrue(DraftBot.pickScore(0.80, DraftBot.urgency(0.35, 0), 0) < techies);
    }

    @Test
    void contestedHeroIsBannedWhenTheyCannotSecureIt() {
        double weFirst = DraftBot.banScore(0.5, 0.3, 0, true, true, 0);
        double theyFirst = DraftBot.banScore(0.5, 0.3, 0, true, false, 0);
        double notTheirs = DraftBot.banScore(0.5, 0.3, 0, false, true, 0);
        assertTrue(weFirst > notTheirs && notTheirs > theyFirst,
                "общий герой банят, если мы заберём раньше, и крадут пиком, если первыми пикают они");
    }

    @Test
    void awkwardHeroBonusGrowsWhenWeHaveNoPicksLeft() {
        assertTrue(DraftBot.awkwardBonus(1, 0) > DraftBot.awkwardBonus(1, 1));
        assertEquals(0, DraftBot.awkwardBonus(0, 0));
    }

    @Test
    void choiceIsAmongTheBestAndRepeatsWithTheSameSeed() {
        List<DraftBot.Candidate> c = List.of(new DraftBot.Candidate(1, 1.0, null, List.of()),
                new DraftBot.Candidate(2, 0.95, null, List.of()), new DraftBot.Candidate(3, 0.92, null, List.of()),
                new DraftBot.Candidate(4, 0.91, null, List.of()), new DraftBot.Candidate(5, 0.5, null, List.of()));
        Set<Integer> seen = new HashSet<>();
        for (long seed = 0; seed < 200; seed++) {
            int h = DraftBot.choose(c, DraftBot.random(seed, 8)).heroId();
            assertTrue(h <= 3, "только три лучших и не слабее 90%");
            seen.add(h);
            assertEquals(h, DraftBot.choose(c, DraftBot.random(seed, 8)).heroId(), "то же зерно — тот же ход");
        }
        assertEquals(3, seen.size(), "разброс между лучшими есть");
    }

    @Test
    void fullSimulationOnRealDataIsValid() throws Exception {
        assumeTrue(Files.isDirectory(Path.of("data/raw")), "нет data/");
        AnalysisContext ctx = AnalysisContext.load(Path.of("config/players.yaml"), Path.of("data"));
        assumeTrue(!ctx.opponents.isEmpty());
        Squad them = ctx.opponents.get(0);
        DraftEngine engine = new DraftEngine(ctx);
        DraftBot bot = new DraftBot(ctx);
        for (boolean weFirst : new boolean[]{true, false}) {
            List<Integer> heroes = simulate(engine, bot, ctx, them, weFirst, 7);
            assertEquals(24, heroes.size());
            assertEquals(24, new HashSet<>(heroes).size(), "герои не повторяются: " + heroes);
            assertEquals(heroes, simulate(engine, bot, ctx, them, weFirst, 7), "то же зерно — тот же драфт");
            DraftBot.Plan plan = bot.plan(ctx.ours, them, weFirst, heroes);
            assertEquals(5, plan.placements().size());
            assertEquals(5, plan.placements().stream().map(DraftBot.Placement::player).distinct().count(),
                    "у каждого игрока свой герой");
        }
    }

    private static List<Integer> simulate(DraftEngine engine, DraftBot bot, AnalysisContext ctx, Squad them,
                                          boolean weFirst, long seed) {
        List<Integer> heroes = new ArrayList<>();
        while (heroes.size() < DraftEngine.SLOTS.size()) {
            DraftEngine.Slot s = DraftEngine.SLOTS.get(heroes.size());
            if (s.firstTeam() == weFirst) {
                DraftEngine.Advice a = engine.advise(new DraftEngine.State(them.slug(), weFirst, heroes));
                List<DraftEngine.Reasoned> list = s.pick() ? a.pickSuggestions() : a.banSuggestions();
                heroes.add(list.get(0).heroId());
            } else {
                DraftBot.Move m = bot.next(ctx.ours, them, weFirst, heroes, seed);
                assertEquals(s.pick(), m.pick());
                if (m.pick()) {
                    assertNotNull(m.player(), "пик бота — конкретному игроку");
                }
                heroes.add(m.heroId());
            }
        }
        return heroes;
    }
}
