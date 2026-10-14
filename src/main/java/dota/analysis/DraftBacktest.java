package dota.analysis;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Бэктест DraftEngine на турнирных матчах группы: проигрываем драфт ход за ходом и смотрим,
 * попадал ли реальный пик команды в прогноз (топ-1/5/10) и угадано ли «кто на каком герое».
 * Турнирная история проверяемого матча из модели исключается. Запуск: {@code ./gradlew draftBacktest}.
 */
public final class DraftBacktest {

    public record Result(int picks, int top1, int top5, int top10, int assigned, int assignedRight, int matches) {

        @Override
        public String toString() {
            return String.format("матчей %d, пиков %d: топ-1 %s, топ-5 %s, топ-10 %s; «кто на герое» %d/%d (%s)",
                    matches, picks, Md.pct(ratio(top1, picks)), Md.pct(ratio(top5, picks)), Md.pct(ratio(top10, picks)),
                    assignedRight, assigned, Md.pct(ratio(assignedRight, assigned)));
        }

        private static double ratio(int a, int b) {
            return b == 0 ? Double.NaN : (double) a / b;
        }
    }

    private DraftBacktest() {
    }

    public static void main(String[] args) throws IOException {
        AnalysisContext ctx = AnalysisContext.load(Path.of("config/players.yaml"), Path.of("data"));
        System.out.println(run(ctx, true));
    }

    static Result run(AnalysisContext ctx, boolean verbose) {
        Map<Long, Squad> squads = new HashMap<>();
        squads.put(ctx.ours.team.teamId(), ctx.ours);
        ctx.opponents.forEach(s -> squads.put(s.team.teamId(), s));
        DraftEngine engine = new DraftEngine(ctx);
        int picks = 0, top1 = 0, top5 = 0, top10 = 0, assigned = 0, right = 0, matches = 0;

        for (League.Match m : ctx.league.matches) {
            Squad radiant = squads.get(m.radiantTeamId()), dire = squads.get(m.direTeamId());
            if (radiant == null || dire == null || !followsOrder(m)) {
                continue;
            }
            matches++;
            boolean firstRadiant = m.draft().get(0).radiant();
            List<Integer> heroes = m.draft().stream().map(League.DraftStep::heroId).toList();
            for (boolean predictRadiant : new boolean[]{true, false}) {
                Squad them = predictRadiant ? radiant : dire, us = predictRadiant ? dire : radiant;
                boolean weFirst = firstRadiant != predictRadiant;
                for (int i = 0; i < heroes.size(); i++) {
                    League.DraftStep step = m.draft().get(i);
                    if (!step.pick() || step.radiant() != predictRadiant) {
                        continue;
                    }
                    DraftEngine.Advice a = engine.advise(us, them, weFirst, heroes.subList(0, i), m.matchId());
                    int rank = rank(a.theirNextPicks(), step.heroId());
                    picks++;
                    top1 += rank == 1 ? 1 : 0;
                    top5 += rank >= 1 && rank <= 5 ? 1 : 0;
                    top10 += rank >= 1 && rank <= 10 ? 1 : 0;
                    if (verbose) {
                        System.out.printf("%d %-12s ход %2d: %-18s место %s%n", m.matchId(), them.team.name(), i + 1,
                                ctx.heroName(step.heroId()), rank < 0 ? ">10" : rank);
                    }
                }
                DraftEngine.Advice full = engine.advise(us, them, weFirst, heroes, m.matchId());
                for (DraftEngine.Assignment as : full.theirAssignment()) {
                    League.Player actual = m.side(predictRadiant).stream()
                            .filter(p -> p.heroId() == as.heroId()).findFirst().orElse(null);
                    if (actual == null) {
                        continue;
                    }
                    PlayerStats ps = them.player(actual.accountId());
                    assigned++;
                    right += ps != null && ps.nick().equals(as.player()) ? 1 : 0;
                }
            }
        }
        return new Result(picks, top1, top5, top10, assigned, right, matches);
    }

    private static int rank(List<DraftEngine.Reasoned> list, int heroId) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).heroId() == heroId) {
                return i + 1;
            }
        }
        return -1;
    }

    /** Только матчи с полным стандартным порядком CM (24 хода в порядке ORDER). */
    static boolean followsOrder(League.Match m) {
        List<League.DraftStep> d = m.draft();
        if (d.size() != DraftEngine.SLOTS.size()) {
            return false;
        }
        boolean firstRadiant = d.get(0).radiant();
        List<Boolean> ok = new ArrayList<>();
        for (int i = 0; i < d.size(); i++) {
            DraftEngine.Slot s = DraftEngine.SLOTS.get(i);
            ok.add(s.pick() == d.get(i).pick() && (s.firstTeam() == (d.get(i).radiant() == firstRadiant)));
        }
        return !ok.contains(false);
    }
}
