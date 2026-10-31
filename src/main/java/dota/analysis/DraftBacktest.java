package dota.analysis;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Бэктест DraftEngine на турнирных матчах группы: проигрываем драфт ход за ходом и смотрим,
 * попадал ли реальный пик команды в прогноз (топ-1/5/10), угадано ли «кто на каком герое» и сколько их реальных
 * банов было в нашем прогнозе банов. Всё сравнивается с простым бейзлайном. Турнирная история проверяемого матча
 * из модели исключается. Запуск: {@code ./gradlew draftBacktest} (с {@code --args="--tune"} — перебор настроек).
 */
public final class DraftBacktest {

    /**
     * Итог. Пики: top-k модели и бейзлайна, logLoss — средний −ln P(реальный пик) у модели.
     * Баны: сколько реальных банов команды попало в топ-7 прогноза модели и бейзлайна на первом ходу.
     */
    public record Result(int picks, int top1, int top5, int top10, double logLoss,
                         int baseTop1, int baseTop5, int baseTop10,
                         int bans, int banHits, int baseBanHits,
                         int assigned, int assignedRight, int matches) {

        @Override
        public String toString() {
            return String.format("""
                            матчей %d, пиков %d
                              модель:   топ-1 %s, топ-5 %s, топ-10 %s, log-loss %.2f (случайный выбор ≈ %.2f)
                              бейзлайн: топ-1 %s, топ-5 %s, топ-10 %s («частые герои игроков за год и патч»)
                            «кто на каком герое»: %d/%d (%s)
                            баны (их реальные баны в нашем топ-7 на первом ходу): модель %d/%d (%s), бейзлайн %d/%d (%s)""",
                    matches, picks, Md.pct(ratio(top1, picks)), Md.pct(ratio(top5, picks)), Md.pct(ratio(top10, picks)),
                    logLoss, Math.log(120),
                    Md.pct(ratio(baseTop1, picks)), Md.pct(ratio(baseTop5, picks)), Md.pct(ratio(baseTop10, picks)),
                    assignedRight, assigned, Md.pct(ratio(assignedRight, assigned)),
                    banHits, bans, Md.pct(ratio(banHits, bans)), baseBanHits, bans, Md.pct(ratio(baseBanHits, bans)));
        }

        private static double ratio(int a, int b) {
            return b == 0 ? Double.NaN : (double) a / b;
        }
    }

    private DraftBacktest() {
    }

    public static void main(String[] args) throws IOException {
        AnalysisContext ctx = AnalysisContext.load(Path.of("config/players.yaml"), Path.of("data"));
        if (args.length > 0 && args[0].equals("--tune")) {
            tune(ctx);
            return;
        }
        System.out.println(run(ctx, DraftEngine.Params.DEFAULT, true));
    }

    /** Перебор настроек модели: печатает топ-5/10 и log-loss для каждой комбинации. */
    static void tune(AnalysisContext ctx) {
        DraftEngine.Params d = DraftEngine.Params.DEFAULT;
        for (double recent : new double[]{0, 4, 8, 16, 32}) {
            for (double team : new double[]{0, 4, 8, 16}) {
                DraftEngine.Params p = new DraftEngine.Params(d.sharpness(), d.shareWeight(), d.shareCap(), recent, team);
                Result r = run(ctx, p, false);
                System.out.printf("recent %4.1f team %4.1f: топ-5 %d топ-10 %d log-loss %.3f, «кто на герое» %d/%d%n",
                        recent, team, r.top5(), r.top10(), r.logLoss(), r.assignedRight(), r.assigned());
            }
        }
    }

    static Result run(AnalysisContext ctx, DraftEngine.Params params, boolean verbose) {
        Map<Long, Squad> squads = new HashMap<>();
        squads.put(ctx.ours.team.teamId(), ctx.ours);
        ctx.opponents.forEach(s -> squads.put(s.team.teamId(), s));
        DraftEngine engine = new DraftEngine(ctx, params);
        int picks = 0, top1 = 0, top5 = 0, top10 = 0, b1 = 0, b5 = 0, b10 = 0, assigned = 0, right = 0, matches = 0;
        int bans = 0, banHits = 0, baseBanHits = 0;
        double logLoss = 0;

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
                    List<Integer> before = heroes.subList(0, i);
                    DraftEngine.Advice a = engine.advise(us, them, weFirst, before, m.matchId());
                    int rank = rank(a.theirNextPicks().stream().map(DraftEngine.Reasoned::heroId).toList(), step.heroId());
                    double p = engine.theirNextDistribution(us, them, weFirst, before, m.matchId())
                            .getOrDefault(step.heroId(), 0.0);
                    logLoss += -Math.log(Math.max(p, 1e-4));
                    int baseRank = rank(baseline(ctx, them, before), step.heroId());
                    picks++;
                    top1 += rank == 1 ? 1 : 0;
                    top5 += rank >= 1 && rank <= 5 ? 1 : 0;
                    top10 += rank >= 1 && rank <= 10 ? 1 : 0;
                    b1 += baseRank == 1 ? 1 : 0;
                    b5 += baseRank >= 1 && baseRank <= 5 ? 1 : 0;
                    b10 += baseRank >= 1 && baseRank <= 10 ? 1 : 0;
                    if (verbose) {
                        System.out.printf("%d %-12s ход %2d: %-18s место %-3s бейзлайн %s%n", m.matchId(), them.team.name(),
                                i + 1, ctx.heroName(step.heroId()), rank < 0 ? ">10" : rank,
                                baseRank < 0 || baseRank > 10 ? ">10" : baseRank);
                    }
                }
                // баны: прогноз на первом ходу против их реальных 7 банов
                Set<Integer> actualBans = new HashSet<>();
                m.draft().stream().filter(d -> !d.pick() && d.radiant() == predictRadiant)
                        .forEach(d -> actualBans.add(d.heroId()));
                // с точки зрения us: theirLikelyBans — что забанит команда them
                DraftEngine.Advice view = engine.advise(us, them, weFirst, List.of(), m.matchId());
                List<Integer> predicted = view.theirLikelyBans().stream().limit(7).map(DraftEngine.Reasoned::heroId).toList();
                bans += actualBans.size();
                banHits += (int) predicted.stream().filter(actualBans::contains).count();
                baseBanHits += (int) baselineBans(ctx, them, m.matchId()).stream().limit(7).filter(actualBans::contains).count();

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
        return new Result(picks, top1, top5, top10, picks == 0 ? 0 : logLoss / picks, b1, b5, b10,
                bans, banHits, baseBanHits, assigned, right, matches);
    }

    /** Бейзлайн пиков: герои, которые игроки команды чаще всего играют за год и патч (сумма долей), без уже занятых. */
    static List<Integer> baseline(AnalysisContext ctx, Squad team, List<Integer> used) {
        Set<Integer> taken = new HashSet<>(used);
        Map<Integer, Double> score = new HashMap<>();
        for (PlayerStats p : team.byPosition()) {
            for (int h : p.heroes.keySet()) {
                if (!taken.contains(h)) {
                    score.merge(h, DraftEngine.share(p, h), Double::sum);
                }
            }
        }
        return score.entrySet().stream().sorted(Map.Entry.<Integer, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey).toList();
    }

    /** Бейзлайн банов: что команда чаще всего банила в других картах турнира. */
    static List<Integer> baselineBans(AnalysisContext ctx, Squad team, long exclude) {
        Map<Integer, Integer> count = new HashMap<>();
        for (League.Match m : ctx.league.of(team.team.teamId())) {
            if (m.matchId() == exclude) {
                continue;
            }
            boolean rad = m.isRadiant(team.team.teamId());
            m.draft().stream().filter(d -> !d.pick() && d.radiant() == rad).forEach(d -> count.merge(d.heroId(), 1, Integer::sum));
        }
        return count.entrySet().stream().sorted(Comparator.comparing(Map.Entry<Integer, Integer>::getValue).reversed())
                .map(Map.Entry::getKey).toList();
    }

    private static int rank(List<Integer> list, int heroId) {
        int i = list.indexOf(heroId);
        return i < 0 ? -1 : i + 1;
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
