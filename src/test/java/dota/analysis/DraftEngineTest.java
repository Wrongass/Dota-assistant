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
    void predictionsFollowRemainingPositions() throws Exception {
        assumeTrue(Files.isDirectory(Path.of("data/raw")), "нет data/");
        AnalysisContext ctx = AnalysisContext.load(Path.of("config/players.yaml"), Path.of("data"));
        // мы первые. Их пики: Slark (Togo, 1), Kunkka (Odar.sp, 3), Dark Willow (Kaiju, 4), Lion (Nate, 5) — свободен
        // только мидер Sacred. Наши: Wraith King, Outworld Devourer (мид), Lich, Vengeful Spirit — свободен Артист.
        List<Integer> heroes = List.of(
                8, 9, 10, 11, 12, 13, 14,        // баны 1–7
                42, 93,                          // P1 WK, P2 Slark
                15, 16, 17,                      // баны
                23, 76, 31, 119, 26, 20,         // P2 Kunkka, P1 OD, P1 Lich, P2 DW, P2 Lion, P1 VS
                18, 19, 21, 22);                 // баны
        DraftEngine.Advice a = new DraftEngine(ctx).advise(new DraftEngine.State("UPPO_TEAM", true, heroes));

        assertEquals(List.of("Sacred"), a.theirRemaining().stream().map(DraftEngine.Remaining::player).toList());
        a.theirNextPicks().stream().limit(5)
                .forEach(r -> assertEquals("Sacred", r.player(), r.name() + " — прогноз не про оставшегося мидера"));
        a.theirLikelyBans().forEach(r -> assertNotEquals("Wrongass", r.player(),
                r.name() + " — мид уже взял OD, его героев банить незачем"));
        assertTrue(a.theirLikelyBans().stream().allMatch(r -> r.player().equals("Артист")),
                "свободен только Артист: " + a.theirLikelyBans());
    }

    @Test
    void theyBanOurHeroesNotTheirOwnHistory() throws Exception {
        assumeTrue(Files.isDirectory(Path.of("data/raw")), "нет data/");
        AnalysisContext ctx = AnalysisContext.load(Path.of("config/players.yaml"), Path.of("data"));
        DraftEngine.Advice a = new DraftEngine(ctx).advise(new DraftEngine.State("UPPO_TEAM", true, List.of()));
        // Bounty Hunter они банили дважды, но у нас на нём никто не играет — в прогнозе их банов его быть не должно
        assertTrue(a.theirLikelyBans().stream().noneMatch(r -> r.name().equals("Bounty Hunter")), a.theirLikelyBans().toString());
        assertTrue(a.theirLikelyBans().stream().anyMatch(r -> r.name().equals("Wraith King") || r.name().equals("Lich")),
                "наши турнирные герои должны быть среди их вероятных банов: " + a.theirLikelyBans());
    }

    @Test
    void backtestAssignsMostHeroesToTheRightPlayers() throws Exception {
        assumeTrue(Files.isDirectory(Path.of("data/raw/league")), "нет матчей лиги");
        AnalysisContext ctx = AnalysisContext.load(Path.of("config/players.yaml"), Path.of("data"));
        DraftBacktest.Result r = DraftBacktest.run(ctx, DraftEngine.Params.DEFAULT, false);
        assumeTrue(r.assigned() > 0, "нет матчей между известными командами");
        assertTrue(r.assignedRight() >= 0.8 * r.assigned(), r.toString());
        assertTrue(r.top10() >= r.baseTop10(), "модель не хуже бейзлайна «частые герои»: " + r);
    }

    @Test
    void bestAssignmentGivesEveryHeroItsOwnPlayer() {
        // по отдельности оба героя «лучше» у игрока 0, но вместе — один у 0, другой у 1
        double[][] w = {{10, 9}, {10, 1}};
        int[] best = DraftEngine.bestAssignment(w);
        assertArrayEquals(new int[]{0, 1}, new int[]{best[1], best[0]}, "герой 1 без вариантов у 0, герой 0 уходит к 1");
    }

    @Test
    void stepsLeftFollowCaptainsModeOrder() {
        assertEquals(7, DraftEngine.left(0, true, false));
        assertEquals(5, DraftEngine.left(0, false, true));
        // после 18 ходов у каждой команды осталось по 2 бана и по 1 пику
        assertEquals(2, DraftEngine.left(18, true, false));
        assertEquals(1, DraftEngine.left(18, false, true));
        assertEquals(0, DraftEngine.left(24, true, true));
    }

    @Test
    void manualAssignmentWinsOverStatistics() throws Exception {
        assumeTrue(Files.isDirectory(Path.of("data/raw")), "нет data/");
        AnalysisContext ctx = AnalysisContext.load(Path.of("config/players.yaml"), Path.of("data"));
        // 7 банов, наш Lich (31), их Mirana (?). Ставим Lich вручную Babayka, хотя статистика за Enaria
        List<Integer> heroes = List.of(8, 9, 10, 11, 12, 13, 14, 31);
        DraftEngine.Advice auto = new DraftEngine(ctx).advise(new DraftEngine.State("SD_Team", true, heroes));
        assertEquals("Enaria", auto.ourAssignment().get(0).player());
        DraftEngine.Advice manual = new DraftEngine(ctx).advise(
                new DraftEngine.State("SD_Team", true, heroes, java.util.Map.of(31, "Babayka")));
        DraftEngine.Assignment a = manual.ourAssignment().get(0);
        assertEquals("Babayka", a.player());
        assertTrue(a.fixed());
        assertEquals(1.0, a.probability(), 1e-9);
        assertTrue(manual.ourRemaining().stream().anyMatch(r -> r.player().equals("Enaria")), "Enaria снова свободна");
    }

    @Test
    void theirBanHabitRaisesOurHeroesTheyAlwaysBan() throws Exception {
        assumeTrue(Files.isDirectory(Path.of("data/raw/league")), "нет матчей лиги");
        AnalysisContext ctx = AnalysisContext.load(Path.of("config/players.yaml"), Path.of("data"));
        DraftEngine.Advice a = new DraftEngine(ctx).advise(new DraftEngine.State("UPPO_TEAM", true, List.of()));
        // UPPO банили Dragon Knight почти во всех картах турнира, а DK — герой нашего мида. После 3-го тура выше него
        // стоят наши частые турнирные герои (VS, Lich, Lifestealer), поэтому проверяем топ-6, а не топ-3
        DraftEngine.Reasoned dk = a.theirLikelyBans().stream().filter(r -> r.name().equals("Dragon Knight")).findFirst()
                .orElseThrow(() -> new AssertionError("нет DK: " + a.theirLikelyBans()));
        assertTrue(dk.score() >= 0.4, "DK забанят с большой вероятностью: " + dk);
        assertTrue(a.theirLikelyBans().indexOf(dk) < 6, "DK в топ-6 их банов: " + a.theirLikelyBans());
        assertEquals(7, a.theirBansLeft());
    }

    @Test
    void finishedDraftHasLanesAndNoBanForecast() throws Exception {
        assumeTrue(Files.isDirectory(Path.of("data/raw")), "нет data/");
        AnalysisContext ctx = AnalysisContext.load(Path.of("config/players.yaml"), Path.of("data"));
        List<Integer> heroes = new ArrayList<>();
        for (int h = 1; heroes.size() < 24; h++) {
            if (ctx.meta.heroes.containsKey(h)) {
                heroes.add(h);
            }
        }
        DraftEngine.Advice a = new DraftEngine(ctx).advise(new DraftEngine.State("SD_Team", true, heroes));
        assertTrue(a.finished());
        assertEquals(3, a.lanes().size());
        assertTrue(a.theirLikelyBans().isEmpty(), "банов у них больше нет");
        assertEquals(5, a.theirThreats().size());
        assertEquals(5, a.ourAssignment().stream().map(DraftEngine.Assignment::player).distinct().count(),
                "в итоговой расстановке у каждого нашего игрока свой герой");
    }

    @Test
    void ourPicksAreHeroesWePlayAndKnownLanesGetLaneOptions() throws Exception {
        assumeTrue(Files.isDirectory(Path.of("data/raw")), "нет data/");
        AnalysisContext ctx = AnalysisContext.load(Path.of("config/players.yaml"), Path.of("data"));
        DraftEngine engine = new DraftEngine(ctx);
        // их пики: Ember Spirit (106) — их мид Caraxes, Windranger (21) — их керри Nynge; остальные ходы пропущены
        List<Integer> heroes = new ArrayList<>(List.of(0, 0, 0, 0, 0, 0, 0, 0, 106, 0, 0, 0, 21));
        DraftEngine.Advice a = engine.advise(new DraftEngine.State("SD_Team", true, heroes,
                java.util.Map.of(106, "Caraxes", 21, "Nynge")));

        for (DraftEngine.Reasoned r : a.pickSuggestions()) {
            PlayerStats p = ctx.ours.byPosition().stream().filter(x -> x.nick().equals(r.player())).findFirst().orElseThrow();
            PlayerStats.HeroLine line = p.heroes.get(r.heroId());
            assertTrue(line != null && line.games >= 1, r.player() + " не играет на " + r.name());
        }
        assumeTrue(ctx.hasLanes(), "нет data/raw/stratz");
        DraftEngine.LaneOption mid = a.laneOptions().stream().filter(o -> o.position() == 2).findFirst().orElseThrow();
        assertEquals(List.of("Ember Spirit"), mid.enemies());
        assertFalse(mid.heroes().isEmpty());
        assertTrue(a.laneOptions().stream().noneMatch(o -> o.position() == 1 || o.position() == 5),
                "их 3 и 4 ещё не известны — нашей лёгкой линии подсказка не нужна");
        assertTrue(a.pickSuggestions().stream().anyMatch(r -> r.reasons().stream().anyMatch(x -> x.startsWith("линия"))),
                "хоть один наш пик объяснён линией");
    }
}
