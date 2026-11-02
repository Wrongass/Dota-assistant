package dota.analysis;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Подробный бэктест прогнозов драфта на всех картах лиги, где хотя бы одна команда есть в конфиге (8 команд):
 * пики (топ-1/5/10, log-loss, по фазам), «кто на каком герое» и «на какой позиции», баны, угадывание состава.
 * Два сценария: состав по конфигу (как на сайте до лобби) и состав известен (реальный состав карты, как после лобби).
 * Запуск: {@code ./gradlew draftBacktest --args="--report"}.
 */
final class DraftBacktestReport {

    /** Счётчики одного сценария (или одной команды). */
    static final class Stats {
        int maps, picks, top1, top5, top10, base5;
        double logLoss;
        final int[] phasePicks = new int[5], phaseTop5 = new int[5];
        int assigned, playerRight, posAssigned, posRight;
        int bans, banHits, baseBanHits;

        void add(Stats o) {
            maps += o.maps;
            picks += o.picks;
            top1 += o.top1;
            top5 += o.top5;
            top10 += o.top10;
            base5 += o.base5;
            logLoss += o.logLoss;
            for (int i = 0; i < 5; i++) {
                phasePicks[i] += o.phasePicks[i];
                phaseTop5[i] += o.phaseTop5[i];
            }
            assigned += o.assigned;
            playerRight += o.playerRight;
            posAssigned += o.posAssigned;
            posRight += o.posRight;
            bans += o.bans;
            banHits += o.banHits;
            baseBanHits += o.baseBanHits;
        }
    }

    private DraftBacktestReport() {
    }

    static String pct(int a, int b) {
        return b == 0 ? "—" : Md.pct((double) a / b);
    }

    /** Короткая сводка пиков по всем картам (состав по конфигу) с параметрами params — для подбора. */
    static String quick(AnalysisContext ctx, DraftEngine.Params params) {
        Map<Long, Squad> squads = new HashMap<>();
        squads.put(ctx.ours.team.teamId(), ctx.ours);
        ctx.opponents.forEach(s -> squads.put(s.team.teamId(), s));
        DraftEngine engine = new DraftEngine(ctx, params);
        Stats s = new Stats();
        for (League.Match m : ctx.league.matches) {
            if (!DraftBacktest.followsOrder(m)) {
                continue;
            }
            boolean firstRadiant = m.draft().get(0).radiant();
            List<Integer> heroes = m.draft().stream().map(League.DraftStep::heroId).toList();
            for (boolean predictRadiant : new boolean[]{true, false}) {
                Squad them = squads.get(predictRadiant ? m.radiantTeamId() : m.direTeamId());
                if (them == null) {
                    continue;
                }
                Squad us = squads.get(predictRadiant ? m.direTeamId() : m.radiantTeamId());
                if (us == null) {
                    us = them == ctx.ours ? ctx.opponents.get(0) : ctx.ours;
                }
                evaluate(ctx, engine, s, us, them, firstRadiant != predictRadiant, heroes, m, predictRadiant, false,
                        TeamLineups.positions(m.side(predictRadiant)));
            }
        }
        StringBuilder phases = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            phases.append(pct(s.phaseTop5[i], s.phasePicks[i])).append(i < 4 ? " / " : "");
        }
        return String.format("пиков %d, топ-1 %s, топ-5 %s, топ-10 %s, log-loss %.3f, топ-5 по пикам %s", s.picks,
                pct(s.top1, s.picks), pct(s.top5, s.picks), pct(s.top10, s.picks), s.logLoss / s.picks, phases);
    }

    static void run(AnalysisContext ctx) throws IOException {
        Map<Long, Squad> squads = new HashMap<>();
        squads.put(ctx.ours.team.teamId(), ctx.ours);
        ctx.opponents.forEach(s -> squads.put(s.team.teamId(), s));
        DraftEngine engine = new DraftEngine(ctx);
        Map<String, Squad> lineupCache = new HashMap<>();

        Stats conf = new Stats(), confSub = new Stats(), known = new Stats();
        Stats mix = new Stats(), mixConf = new Stats(), mixKnown = new Stats();
        Map<String, Stats> byTeam = new LinkedHashMap<>();
        int[] lwTotal = new int[6], lwRight = new int[6];
        int lwMaps = 0;
        double lwChance = 0;
        int skippedOrder = 0, skippedLineup = 0;

        for (League.Match m : ctx.league.matches) {
            boolean anyKnown = squads.containsKey(m.radiantTeamId()) || squads.containsKey(m.direTeamId());
            if (!anyKnown) {
                continue;
            }
            if (!DraftBacktest.followsOrder(m)) {
                skippedOrder++;
                continue;
            }
            boolean firstRadiant = m.draft().get(0).radiant();
            List<Integer> heroes = m.draft().stream().map(League.DraftStep::heroId).toList();
            for (boolean predictRadiant : new boolean[]{true, false}) {
                long tid = predictRadiant ? m.radiantTeamId() : m.direTeamId();
                Squad them = squads.get(tid);
                if (them == null) {
                    continue;
                }
                Squad us = squads.get(predictRadiant ? m.direTeamId() : m.radiantTeamId());
                boolean usKnown = us != null;
                if (!usKnown) {
                    us = them == ctx.ours ? ctx.opponents.get(0) : ctx.ours; // соперник не из конфига: подставляем
                }
                boolean weFirst = firstRadiant != predictRadiant;
                Map<Integer, Long> actual = TeamLineups.positions(m.side(predictRadiant));
                Set<Long> ids = new HashSet<>();
                them.players.forEach(p -> ids.add(p.config.accountId()));
                them.bench.forEach(p -> ids.add(p.config.accountId()));
                Squad themKnown = null;
                if (actual != null && ids.containsAll(actual.values())) {
                    String key = tid + ":" + actual;
                    themKnown = lineupCache.get(key);
                    if (themKnown == null) {
                        themKnown = ctx.withLineup(them, actual);
                        lineupCache.put(key, themKnown);
                    }
                } else {
                    skippedLineup++;
                }
                Stats one = new Stats();
                evaluate(ctx, engine, one, us, them, weFirst, heroes, m, predictRadiant, usKnown, actual);
                conf.add(one);
                byTeam.computeIfAbsent(them.team.name(), k -> new Stats()).add(one);
                if (themKnown != null) {
                    confSub.add(one);
                    Stats k = new Stats();
                    evaluate(ctx, engine, k, us, themKnown, weFirst, heroes, m, predictRadiant, usKnown, actual);
                    known.add(k);
                }

                // угадывание состава по пикам (только у соперников с несколькими составами в турнире)
                List<AnalysisContext.TeamLineup> lineups = ctx.lineupsOf(them);
                if (themKnown != null && lineups.size() >= 2) {
                    String actualKey = AnalysisContext.lineupKey(themKnown);
                    List<String> keys = lineups.stream().map(AnalysisContext.TeamLineup::key).toList();
                    if (keys.contains(actualKey)) {
                        // прогноз пиков «все составы с весами» против конфига и известного состава на тех же картах
                        mixConf.add(one);
                        Stats kk = new Stats();
                        evaluate(ctx, engine, kk, us, themKnown, weFirst, heroes, m, predictRadiant, false, null);
                        mixKnown.add(kk);
                        mixPicks(engine, mix, us, lineups, weFirst, heroes, m, predictRadiant);
                        lwMaps++;
                        lwChance += 1.0 / keys.size();
                        List<Squad> sq = lineups.stream().map(AnalysisContext.TeamLineup::squad).toList();
                        int seen = 0;
                        for (int i = 0; i <= heroes.size(); i++) {
                            boolean theirPickJustMade = i > 0 && m.draft().get(i - 1).pick() && m.draft().get(i - 1).radiant() == predictRadiant;
                            if (i > 0 && !theirPickJustMade) {
                                continue;
                            }
                            double[] w = engine.lineupWeights(sq, keys, null, weFirst, heroes.subList(0, i), Map.of(), m.matchId());
                            int best = 0;
                            for (int j = 1; j < w.length; j++) {
                                best = w[j] > w[best] ? j : best;
                            }
                            lwTotal[seen]++;
                            lwRight[seen] += keys.get(best).equals(actualKey) ? 1 : 0;
                            seen++;
                        }
                    }
                }
            }
        }

        StringBuilder out = new StringBuilder();
        out.append("Карт с командами из конфига и стандартным порядком CM: ").append(conf.maps)
                .append(" (сторон), пропущено с другим порядком ходов: ").append(skippedOrder)
                .append(", без определимого состава: ").append(skippedLineup).append("\n\n");
        out.append("| Сценарий | Карт | Пиков | Топ-1 | Топ-5 | Топ-10 | log-loss | Бейзлайн топ-5 | Кто на герое | Позиция героя | Баны в топ-7 (бейзлайн) |\n");
        out.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        row(out, "Состав по конфигу, все карты", conf);
        row(out, "Состав по конфигу, карты с известным составом", confSub);
        row(out, "Состав известен (после лобби)", known);
        out.append("\nПо фазам (топ-5, состав по конфигу): ");
        for (int i = 0; i < 5; i++) {
            out.append(i + 1).append("-й пик ").append(pct(conf.phaseTop5[i], conf.phasePicks[i])).append(i < 4 ? ", " : "\n");
        }
        out.append("По фазам (топ-5, состав известен): ");
        for (int i = 0; i < 5; i++) {
            out.append(i + 1).append("-й пик ").append(pct(known.phaseTop5[i], known.phasePicks[i])).append(i < 4 ? ", " : "\n");
        }
        out.append("\n| Команда | Карт | Пиков | Топ-5 | Топ-10 | Бейзлайн топ-5 | Кто на герое | Позиция героя |\n|---|---|---|---|---|---|---|---|\n");
        byTeam.forEach((name, s) -> out.append("| ").append(name).append(" | ").append(s.maps).append(" | ").append(s.picks)
                .append(" | ").append(pct(s.top5, s.picks)).append(" | ").append(pct(s.top10, s.picks))
                .append(" | ").append(pct(s.base5, s.picks)).append(" | ").append(pct(s.playerRight, s.assigned))
                .append(" | ").append(pct(s.posRight, s.posAssigned)).append(" |\n"));
        out.append("\nУгадывание состава (соперники с несколькими составами, карт: ").append(lwMaps)
                .append(", случайный выбор ").append(lwMaps == 0 ? "—" : Md.pct(lwChance / lwMaps)).append("): ");
        for (int k = 0; k < 6; k++) {
            out.append(k == 0 ? "до пиков " : "после " + k + "-го пика ").append(pct(lwRight[k], lwTotal[k])).append(k < 5 ? ", " : "\n");
        }
        out.append("\nСоперники с несколькими составами, прогноз пиков (карт ").append(mix.maps).append("): топ-5 / топ-10 — ")
                .append("по конфигу ").append(pct(mixConf.top5, mixConf.picks)).append(" / ").append(pct(mixConf.top10, mixConf.picks))
                .append(", все составы с весами ").append(pct(mix.top5, mix.picks)).append(" / ").append(pct(mix.top10, mix.picks))
                .append(", состав известен ").append(pct(mixKnown.top5, mixKnown.picks)).append(" / ").append(pct(mixKnown.top10, mixKnown.picks))
                .append("\n");
        System.out.print(out);
    }

    /** Прогноз их пиков в режиме «все составы»: советы по каждому составу, сложенные с весами по уже сделанным пикам. */
    private static void mixPicks(DraftEngine engine, Stats s, Squad us, List<AnalysisContext.TeamLineup> lineups, boolean weFirst,
                                 List<Integer> heroes, League.Match m, boolean predictRadiant) {
        s.maps++;
        List<Squad> sq = lineups.stream().map(AnalysisContext.TeamLineup::squad).toList();
        List<String> keys = lineups.stream().map(AnalysisContext.TeamLineup::key).toList();
        for (int i = 0; i < heroes.size(); i++) {
            League.DraftStep step = m.draft().get(i);
            if (!step.pick() || step.radiant() != predictRadiant) {
                continue;
            }
            List<Integer> before = heroes.subList(0, i);
            double[] w = engine.lineupWeights(sq, keys, null, weFirst, before, Map.of(), m.matchId());
            List<DraftEngine.Advice> each = new java.util.ArrayList<>();
            for (Squad x : sq) {
                each.add(engine.advise(us, x, weFirst, before, m.matchId()));
            }
            List<Integer> predicted = DraftEngine.mergeReasoned(each, w, DraftEngine.Advice::theirNextPicks, 10).stream()
                    .map(DraftEngine.Reasoned::heroId).toList();
            int rank = predicted.indexOf(step.heroId()) + 1;
            s.picks++;
            s.top5 += rank >= 1 && rank <= 5 ? 1 : 0;
            s.top10 += rank >= 1 && rank <= 10 ? 1 : 0;
        }
    }

    private static void row(StringBuilder out, String name, Stats s) {
        out.append("| ").append(name).append(" | ").append(s.maps).append(" | ").append(s.picks).append(" | ")
                .append(pct(s.top1, s.picks)).append(" | ").append(pct(s.top5, s.picks)).append(" | ")
                .append(pct(s.top10, s.picks)).append(" | ")
                .append(s.picks == 0 ? "—" : String.format("%.2f", s.logLoss / s.picks)).append(" | ")
                .append(pct(s.base5, s.picks)).append(" | ").append(pct(s.playerRight, s.assigned)).append(" | ")
                .append(pct(s.posRight, s.posAssigned)).append(" | ")
                .append(pct(s.banHits, s.bans)).append(" (").append(pct(s.baseBanHits, s.bans)).append(") |\n");
    }

    private static void evaluate(AnalysisContext ctx, DraftEngine engine, Stats s, Squad us, Squad them, boolean weFirst,
                                 List<Integer> heroes, League.Match m, boolean predictRadiant, boolean usKnown,
                                 Map<Integer, Long> actual) {
        s.maps++;
        int phase = 0;
        for (int i = 0; i < heroes.size(); i++) {
            League.DraftStep step = m.draft().get(i);
            if (!step.pick() || step.radiant() != predictRadiant) {
                continue;
            }
            List<Integer> before = heroes.subList(0, i);
            DraftEngine.Advice a = engine.advise(us, them, weFirst, before, m.matchId());
            List<Integer> predicted = a.theirNextPicks().stream().map(DraftEngine.Reasoned::heroId).toList();
            int rank = predicted.indexOf(step.heroId()) + 1;
            double p = engine.theirNextDistribution(us, them, weFirst, before, m.matchId()).getOrDefault(step.heroId(), 0.0);
            s.logLoss += -Math.log(Math.max(p, 1e-4));
            int baseRank = DraftBacktest.baseline(ctx, them, before).indexOf(step.heroId()) + 1;
            s.picks++;
            s.top1 += rank == 1 ? 1 : 0;
            s.top5 += rank >= 1 && rank <= 5 ? 1 : 0;
            s.top10 += rank >= 1 && rank <= 10 ? 1 : 0;
            s.base5 += baseRank >= 1 && baseRank <= 5 ? 1 : 0;
            s.phasePicks[Math.min(phase, 4)]++;
            s.phaseTop5[Math.min(phase, 4)] += rank >= 1 && rank <= 5 ? 1 : 0;
            phase++;
        }
        if (usKnown) {
            Set<Integer> actualBans = m.draft().stream().filter(d -> !d.pick() && d.radiant() == predictRadiant)
                    .map(League.DraftStep::heroId).collect(Collectors.toSet());
            DraftEngine.Advice view = engine.advise(us, them, weFirst, List.of(), m.matchId());
            s.bans += actualBans.size();
            s.banHits += (int) view.theirLikelyBans().stream().limit(7).map(DraftEngine.Reasoned::heroId).filter(actualBans::contains).count();
            s.baseBanHits += (int) DraftBacktest.baselineBans(ctx, them, m.matchId()).stream().limit(7).filter(actualBans::contains).count();
        }
        DraftEngine.Advice full = engine.advise(us, them, weFirst, heroes, m.matchId());
        Map<Long, Integer> actualPos = new HashMap<>();
        if (actual != null) {
            actual.forEach((pos, id) -> actualPos.put(id, pos));
        }
        for (DraftEngine.Assignment as : full.theirAssignment()) {
            League.Player real = m.side(predictRadiant).stream().filter(p -> p.heroId() == as.heroId()).findFirst().orElse(null);
            if (real == null) {
                continue;
            }
            PlayerStats ps = them.player(real.accountId());
            s.assigned++;
            s.playerRight += ps != null && ps.nick().equals(as.player()) ? 1 : 0;
            Integer realPos = actualPos.get(real.accountId());
            PlayerStats predicted = them.players.stream().filter(p -> p.nick().equals(as.player())).findFirst().orElse(null);
            if (realPos != null && predicted != null) {
                s.posAssigned++;
                s.posRight += them.position(predicted) == realPos ? 1 : 0;
            }
        }
    }
}
