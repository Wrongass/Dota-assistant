package dota.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Тест модели драфта на противоречивых данных (docs/research/contradictory-data.md). Берёт два файла:
 * настоящие карты нашей команды и специально неправильный набор (тысяча «отравленных» карт, где наши игроки берут
 * чужих героев и всегда побеждают, плюс битые записи). Подмешивает неправильные данные к настоящим разными дозами и
 * смотрит, как меняется прогноз наших пиков на настоящих картах и прогноз для остальных команд.
 * Запуск: {@code ./gradlew draftBacktest --args="--contradict"}.
 */
final class ContradictoryDataCheck {

    private static final ObjectMapper JSON = new ObjectMapper();
    /**
     * Настоящие карты лиги (до подмешивания): по ним и считается точность. Именно карты, а не id: поддельный дубль
     * с тем же match_id не должен попасть в проверку.
     */
    private static final Set<League.Match> ORIGINAL = new HashSet<>();
    static final Path REAL = Path.of("testdata/contradictory/real_team_visual.json");
    static final Path FAKE = Path.of("testdata/contradictory/fake_team_visual.json");

    private ContradictoryDataCheck() {
    }

    /** Точность прогноза пиков одной группы сторон (наша команда или все остальные). */
    record Score(int picks, int top1, int top5, int top10, double logLoss, int alien5, int assigned, int right,
                 int bans, int banHits) {
        String row(String name) {
            return String.format("| %s | %d | %s | %s | %s | %.2f | %s | %s | %s |", name, picks, pct(top1, picks),
                    pct(top5, picks), pct(top10, picks), logLoss, pct(alien5, picks * 5), pct(right, assigned),
                    pct(banHits, bans));
        }
    }

    static void main(String[] args) throws IOException {
        AnalysisContext ctx = AnalysisContext.load(Path.of("config/players.yaml"), Path.of("data"));
        List<JsonNode> realJson = read(REAL), fakeJson = read(FAKE);
        StringBuilder out = new StringBuilder();

        // 1. настоящие данные совпадают с источником (OpenDota)
        Map<Long, League.Match> loaded = new HashMap<>();
        ctx.league.matches.forEach(m -> loaded.put(m.matchId(), m));
        int same = 0;
        List<String> diff = new ArrayList<>();
        Set<Long> realIds = new HashSet<>();
        for (JsonNode j : realJson) {
            League.Match r = League.parse(j);
            if (r == null) {
                diff.add(j.path("match_id").asText() + ": переигровка, в модель не идёт");
                continue;
            }
            realIds.add(r.matchId());
            League.Match l = loaded.get(r.matchId());
            if (l != null && l.radiantWin() == r.radiantWin() && l.draft().equals(r.draft())) {
                same++;
            } else {
                diff.add(r.matchId() + ": не совпадает с data/raw");
            }
        }
        out.append(String.format("Настоящий файл: %d карт, совпадают с выгрузкой %d%s%n", realJson.size(), same,
                diff.isEmpty() ? "" : ", расхождения: " + diff));

        // 2. битые записи: что пропускает загрузчик
        Set<Integer> heroIds = new HashSet<>(ctx.meta.heroIdsByName());
        Map<String, int[]> cases = new LinkedHashMap<>(); // [всего, принято парсером, отсеяно проверкой]
        List<League.Match> poison = new ArrayList<>(), plausible = new ArrayList<>(), broken = new ArrayList<>();
        for (JsonNode j : fakeJson) {
            String c = j.path("_case").asText();
            int[] n = cases.computeIfAbsent(c, k -> new int[3]);
            n[0]++;
            League.Match m;
            try {
                m = League.parse(j);
            } catch (RuntimeException e) {
                continue;
            }
            if (m == null) {
                continue;
            }
            n[1]++;
            if (LeagueCheck.problem(m, heroIds, loaded.keySet()) != null) {
                n[2]++;
            }
            (c.equals("poison") ? poison : c.equals("poison_plausible") ? plausible : broken).add(m);
        }
        out.append("\nБитые записи: | вид | всего | парсер принял | проверка отсеяла |\n");
        cases.forEach((c, n) -> out.append(String.format("| %s | %d | %d | %d |%n", c, n[0], n[1], n[2])));

        // 3. дозы отравления: без защиты и с защитой (LeagueCheck)
        long ourId = ctx.ours.team.teamId();
        Set<Integer> alien = alienHeroes(ctx, poison, ourId);
        out.append("\n| Данные | Пиков | Топ-1 | Топ-5 | Топ-10 | log-loss | чужие герои в топ-5 | кто на герое | баны в топ-7 |\n");
        List<League.Match> original = new ArrayList<>(ctx.league.matches);
        ORIGINAL.addAll(original);
        Map<String, List<League.Match>> scenarios = new LinkedHashMap<>();
        scenarios.put("только настоящие", List.of());
        for (int dose : new int[]{10, 100, 1000}) {
            scenarios.put("+ " + dose + " с чужими героями", poison.subList(0, dose));
        }
        List<League.Match> all = new ArrayList<>(poison);
        all.addAll(broken);
        scenarios.put("+ 1000 с чужими героями + битые", all);
        scenarios.put("+ 300 правдоподобных подделок", plausible);
        for (var e : scenarios.entrySet()) {
            for (boolean guard : e.getValue().isEmpty() ? new boolean[]{false} : new boolean[]{false, true}) {
                String note = inject(ctx, original, e.getValue(), guard, heroIds);
                DraftEngine engine = new DraftEngine(ctx, DraftEngine.Params.DEFAULT);
                String name = e.getKey() + (guard ? ", с проверкой" : "");
                out.append(score(ctx, engine, realIds, true, ourId, alien).row(name + ": наши пики")).append('\n');
                out.append(score(ctx, engine, realIds, false, ourId, alien).row(name + ": другие команды")).append('\n');
                if (!note.isEmpty()) {
                    out.append("|   ").append(note).append(" ||||||||\n");
                }
            }
        }
        ctx.league.matches.clear();
        ctx.league.matches.addAll(original);
        System.out.println(out);
    }

    /** Подменить карты лиги: настоящие + extra (с проверкой — только прошедшие LeagueCheck). */
    private static String inject(AnalysisContext ctx, List<League.Match> original, List<League.Match> extra,
                                 boolean guard, Set<Integer> heroIds) {
        ctx.league.matches.clear();
        ctx.league.matches.addAll(original);
        if (!guard) {
            ctx.league.matches.addAll(extra);
            ctx.league.matches.sort(Comparator.comparingLong(League.Match::startTime));
            return "";
        }
        Set<Long> ids = new HashSet<>();
        original.forEach(m -> ids.add(m.matchId()));
        int structural = 0;
        for (League.Match m : extra) {
            if (LeagueCheck.problem(m, heroIds, ids) != null) {
                structural++;
            } else {
                ids.add(m.matchId());
                ctx.league.matches.add(m);
            }
        }
        ctx.league.matches.sort(Comparator.comparingLong(League.Match::startTime));
        List<Squad> squads = new ArrayList<>(ctx.opponents);
        squads.add(ctx.ours);
        LeagueCheck.Report r = LeagueCheck.clean(ctx.league, heroIds, squads);
        return String.format("проверка: битых отсеяно %d, неправдоподобных отсеяно %d, настоящих отсеяно %d; %s",
                structural, r.removed().size(), r.removed().stream().filter(original::contains).count(),
                String.join("; ", r.warnings()));
    }

    /** Герои, которые подделка приписывает нашим игрокам (их анти-пул). */
    private static Set<Integer> alienHeroes(AnalysisContext ctx, List<League.Match> poison, long ourId) {
        Set<Integer> s = new HashSet<>();
        for (League.Match m : poison) {
            m.side(m.isRadiant(ourId)).forEach(p -> s.add(p.heroId()));
        }
        return s;
    }

    /**
     * Прогон драфта по настоящим картам (из realIds — только наши, иначе — все карты лиги между командами конфига, кроме
     * наших): перед каждым пиком нужной стороны — место реального героя в «Что они возьмут».
     */
    private static Score score(AnalysisContext ctx, DraftEngine engine, Set<Long> realIds, boolean ours, long ourId,
                               Set<Integer> alien) {
        Map<Long, Squad> squads = new HashMap<>();
        squads.put(ourId, ctx.ours);
        ctx.opponents.forEach(s -> squads.put(s.team.teamId(), s));
        int picks = 0, top1 = 0, top5 = 0, top10 = 0, alien5 = 0, assigned = 0, right = 0, bans = 0, banHits = 0;
        double ll = 0;
        List<League.Match> maps = ctx.league.matches.stream()
                .filter(m -> ORIGINAL.contains(m) && (ours ? realIds.contains(m.matchId()) : !m.involves(ourId)))
                .filter(DraftBacktest::followsOrder).toList();
        for (League.Match m : maps) {
            boolean firstRadiant = m.draft().get(0).radiant();
            List<Integer> heroes = m.draft().stream().map(League.DraftStep::heroId).toList();
            for (boolean predictRadiant : new boolean[]{true, false}) {
                long themId = predictRadiant ? m.radiantTeamId() : m.direTeamId();
                long usId = predictRadiant ? m.direTeamId() : m.radiantTeamId();
                if (ours != (themId == ourId)) {
                    continue;
                }
                Squad them = squads.get(themId);
                // соперник не из конфига (выбывшие команды плей-ина) — подставляем любую другую команду: прогноз наших
                // пиков от неё почти не зависит
                Squad us = squads.getOrDefault(usId, ctx.opponents.get(0));
                if (them == null) {
                    continue;
                }
                boolean weFirst = firstRadiant != predictRadiant;
                for (int i = 0; i < heroes.size(); i++) {
                    League.DraftStep step = m.draft().get(i);
                    if (!step.pick() || step.radiant() != predictRadiant) {
                        continue;
                    }
                    List<Integer> before = heroes.subList(0, i);
                    DraftEngine.Advice a = engine.advise(us, them, weFirst, before, m.matchId());
                    List<Integer> list = a.theirNextPicks().stream().map(DraftEngine.Reasoned::heroId).toList();
                    int rank = list.indexOf(step.heroId()) + 1;
                    double p = engine.theirNextDistribution(us, them, weFirst, before, m.matchId()).getOrDefault(step.heroId(), 0.0);
                    ll += -Math.log(Math.max(p, 1e-4));
                    picks++;
                    top1 += rank == 1 ? 1 : 0;
                    top5 += rank >= 1 && rank <= 5 ? 1 : 0;
                    top10 += rank >= 1 && rank <= 10 ? 1 : 0;
                    alien5 += (int) list.stream().limit(5).filter(alien::contains).count();
                }
                Set<Integer> actualBans = new HashSet<>();
                m.draft().stream().filter(d -> !d.pick() && d.radiant() == predictRadiant).forEach(d -> actualBans.add(d.heroId()));
                DraftEngine.Advice view = engine.advise(us, them, weFirst, List.of(), m.matchId());
                bans += actualBans.size();
                banHits += (int) view.theirLikelyBans().stream().limit(7).map(DraftEngine.Reasoned::heroId)
                        .filter(actualBans::contains).count();
                DraftEngine.Advice full = engine.advise(us, them, weFirst, heroes, m.matchId());
                for (DraftEngine.Assignment as : full.theirAssignment()) {
                    League.Player actual = m.side(predictRadiant).stream().filter(x -> x.heroId() == as.heroId()).findFirst().orElse(null);
                    if (actual != null) {
                        PlayerStats ps = them.player(actual.accountId());
                        assigned++;
                        right += ps != null && ps.nick().equals(as.player()) ? 1 : 0;
                    }
                }
            }
        }
        return new Score(picks, top1, top5, top10, picks == 0 ? 0 : ll / picks, alien5, assigned, right, bans, banHits);
    }

    private static List<JsonNode> read(Path f) throws IOException {
        List<JsonNode> list = new ArrayList<>();
        JSON.readTree(f.toFile()).forEach(list::add);
        return list;
    }

    private static String pct(int a, int b) {
        return b == 0 ? "—" : Math.round(100.0 * a / b) + "%";
    }
}
