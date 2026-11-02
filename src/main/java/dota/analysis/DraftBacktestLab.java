package dota.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Эксперименты с прогнозом драфта: много вариантов параметров за один проход по картам лиги.
 * <ul>
 *   <li>{@code --league-config} — собрать data/backtest/league.yaml: основной конфиг плюс все команды лиги, чьи игроки
 *   скачаны облегчённо ({@code ./gradlew run --args="--league-players"}). Позиции — по последней карте команды.</li>
 *   <li>{@code --config <yaml>} — на каком конфиге гонять (по умолчанию config/players.yaml).</li>
 *   <li>{@code --honest} — честный режим: статистика игроков, турнирная история и баны лиги только до дня карты.</li>
 *   <li>{@code --exp picks|bans|lineup|all} — какие варианты считать.</li>
 * </ul>
 * Запуск: {@code ./gradlew draftBacktest --args="--lab --config data/backtest/league.yaml --honest"}.
 */
final class DraftBacktestLab {

    enum LineupMode { CONFIG, LAST, KNOWN, MIX, MIX_RECENT }

    record Variant(String name, DraftEngine.Params params, LineupMode mode, boolean bans) {
    }

    /** Счётчики варианта: все стороны и отдельно 8 команд основного конфига. */
    static final class Stats {
        int sides, picks, top1, top5, top10, base5;
        double logLoss;
        final int[] phasePicks = new int[5], phaseTop5 = new int[5];
        int assigned, playerRight, posAssigned, posRight;
        int bans, banHits, baseBanHits, leagueBanHits;
    }

    private DraftBacktestLab() {
    }

    static void main(String[] args) throws IOException {
        Path config = Path.of("config/players.yaml");
        boolean honest = false;
        String exp = "all";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config" -> config = Path.of(args[++i]);
                case "--honest" -> honest = true;
                case "--exp" -> exp = args[++i];
                case "--league-config" -> {
                    writeLeagueConfig(Path.of("config/players.yaml"), Path.of("data"), Path.of("data/backtest/league.yaml"));
                    return;
                }
                default -> {
                }
            }
        }
        run(config, Path.of("data"), honest, variants(exp));
    }

    static List<Variant> variants(String exp) {
        DraftEngine.Params d = DraftEngine.Params.DEFAULT;
        List<Variant> v = new ArrayList<>();
        v.add(new Variant("база", d, LineupMode.CONFIG, true));
        if (exp.equals("picks") || exp.equals("all")) {
            for (double c : new double[]{0, 4, 12, 16}) {
                v.add(new Variant("контекст " + c, d.with("contextWeight", c), LineupMode.CONFIG, false));
            }
            for (double f : new double[]{1, 2, 4, 8}) {
                v.add(new Variant("первый пик " + f, d.with("firstPickWeight", f), LineupMode.CONFIG, false));
            }
            for (double o : new double[]{0, 0.5, 1.5}) {
                v.add(new Variant("порядок " + o, d.with("orderWeight", o), LineupMode.CONFIG, false));
            }
        }
        if (exp.equals("lineup") || exp.equals("all")) {
            v.add(new Variant("состав: последний", d, LineupMode.LAST, false));
            v.add(new Variant("состав: все поровну", d, LineupMode.MIX, false));
            v.add(new Variant("состав: все, свежий вероятнее", d, LineupMode.MIX_RECENT, false));
            v.add(new Variant("состав: известен", d, LineupMode.KNOWN, false));
        }
        if (exp.equals("bans") || exp.equals("all")) {
            for (double x : new double[]{0.5, 1, 2, 3}) {
                v.add(new Variant("баны: лига " + x, d.with("banLeague", x), LineupMode.CONFIG, true));
            }
            for (double x : new double[]{1, 3}) {
                v.add(new Variant("баны: про " + x, d.with("banPro", x), LineupMode.CONFIG, true));
            }
            for (double x : new double[]{0, 0.5, 2}) {
                v.add(new Variant("баны: привычка " + x, d.with("banHabit", x), LineupMode.CONFIG, true));
            }
            for (double x : new double[]{0, 0.5, 2}) {
                v.add(new Variant("баны: угроза " + x, d.with("banThreat", x), LineupMode.CONFIG, true));
            }
            v.add(new Variant("баны: лига 1, привычка 2", d.with("banLeague", 1).with("banHabit", 2), LineupMode.CONFIG, true));
            v.add(new Variant("баны: лига 2, привычка 2", d.with("banLeague", 2).with("banHabit", 2), LineupMode.CONFIG, true));
            v.add(new Variant("баны: лига 2, угроза 0.5", d.with("banLeague", 2).with("banThreat", 0.5), LineupMode.CONFIG, true));
        }
        if (exp.startsWith("custom:")) {
            // custom:name=value,name=value — один вариант против базы
            DraftEngine.Params p = d;
            for (String kv : exp.substring(7).split(",")) {
                String[] parts = kv.split("=");
                p = p.with(parts[0], Double.parseDouble(parts[1]));
            }
            v.add(new Variant(exp, p, LineupMode.CONFIG, true));
            v.add(new Variant(exp + " + последний состав", p, LineupMode.LAST, false));
        }
        return v;
    }

    static void run(Path configPath, Path dataDir, boolean honest, List<Variant> variants) throws IOException {
        long started = System.currentTimeMillis();
        AnalysisContext base = AnalysisContext.load(configPath, dataDir);
        Set<Long> mainTeams = mainTeamIds();
        Map<LocalDate, List<League.Match>> byDay = new TreeMap<>();
        for (League.Match m : base.league.matches) {
            if (DraftBacktest.followsOrder(m)) {
                byDay.computeIfAbsent(honest ? Instant.ofEpochSecond(m.startTime()).atZone(ZoneOffset.UTC).toLocalDate()
                        : LocalDate.MIN, k -> new ArrayList<>()).add(m);
            }
        }
        Map<String, Stats> all = new LinkedHashMap<>(), main = new LinkedHashMap<>();
        variants.forEach(v -> {
            all.put(v.name(), new Stats());
            main.put(v.name(), new Stats());
        });
        for (var day : byDay.entrySet()) {
            AnalysisContext ctx = honest ? AnalysisContext.loadAsOf(configPath, dataDir, day.getKey()) : base;
            Map<Long, Squad> squads = new HashMap<>();
            squads.put(ctx.ours.team.teamId(), ctx.ours);
            ctx.opponents.forEach(s -> squads.put(s.team.teamId(), s));
            Map<String, DraftEngine> engines = new HashMap<>();
            Map<String, Squad> lineupSquads = new HashMap<>();
            for (League.Match m : day.getValue()) {
                for (boolean predictRadiant : new boolean[]{true, false}) {
                    long tid = predictRadiant ? m.radiantTeamId() : m.direTeamId();
                    Squad them = squads.get(tid), us = squads.get(predictRadiant ? m.direTeamId() : m.radiantTeamId());
                    if (them == null || us == null || them.players.size() < 5 || us.players.size() < 5) {
                        continue;
                    }
                    for (Variant v : variants) {
                        DraftEngine engine = engines.computeIfAbsent(v.params().toString(), k -> new DraftEngine(ctx, v.params()));
                        Stats s = all.get(v.name());
                        Stats s8 = mainTeams.contains(tid) ? main.get(v.name()) : null;
                        evaluate(ctx, engine, v, s, s8, us, them, m, predictRadiant, lineupSquads);
                    }
                }
            }
            System.err.printf("%s: %d карт, %d с начала%n", day.getKey(), day.getValue().size(),
                    (System.currentTimeMillis() - started) / 1000);
        }
        print("Все стороны с известными командами" + (honest ? " (честно, на дату карты)" : ""), all);
        print("Только 8 команд основного конфига" + (honest ? " (честно)" : ""), main);
    }

    private static Set<Long> mainTeamIds() throws IOException {
        dota.fetch.PlayerConfig c = dota.fetch.PlayerConfig.load(Path.of("config/players.yaml"));
        Set<Long> ids = new HashSet<>();
        ids.add(c.ourTeam().teamId());
        c.league().opponentsOrEmpty().forEach(t -> ids.add(t.teamId()));
        return ids;
    }

    private static void evaluate(AnalysisContext ctx, DraftEngine engine, Variant v, Stats s, Stats s8, Squad us, Squad them,
                                 League.Match m, boolean predictRadiant, Map<String, Squad> lineupSquads) throws IOException {
        long tid = them.team.teamId();
        boolean weFirst = m.draft().get(0).radiant() != predictRadiant;
        List<Integer> heroes = m.draft().stream().map(League.DraftStep::heroId).toList();
        Map<Integer, Long> actual = TeamLineups.positions(m.side(predictRadiant));
        Set<Long> known = new HashSet<>();
        them.players.forEach(p -> known.add(p.config.accountId()));
        them.bench.forEach(p -> known.add(p.config.accountId()));
        List<League.Match> before = ctx.league.of(tid).stream().filter(x -> x.startTime() < m.startTime()).toList();
        List<TeamLineups.Seen> seen = TeamLineups.seen(before, tid, known);

        // какой командой (составами) прогнозируем
        List<Squad> lineups = new ArrayList<>();
        double[] prior = null;
        switch (v.mode()) {
            case CONFIG -> lineups.add(them);
            case LAST -> lineups.add(seen.isEmpty() ? them : withLineup(ctx, them, seen.get(0).lineup(), lineupSquads));
            case KNOWN -> {
                if (actual == null || !known.containsAll(actual.values())) {
                    return;
                }
                lineups.add(withLineup(ctx, them, actual, lineupSquads));
            }
            case MIX, MIX_RECENT -> {
                if (seen.size() < 2) {
                    lineups.add(seen.isEmpty() ? them : withLineup(ctx, them, seen.get(0).lineup(), lineupSquads));
                } else {
                    prior = new double[seen.size()];
                    for (int i = 0; i < seen.size(); i++) {
                        lineups.add(withLineup(ctx, them, seen.get(i).lineup(), lineupSquads));
                        prior[i] = v.mode() == LineupMode.MIX_RECENT ? Math.pow(0.5, i) : 1;
                    }
                }
            }
        }
        List<String> keys = lineups.stream().map(AnalysisContext::lineupKey).toList();

        add(s, s8, x -> x.sides++);
        int phase = 0;
        for (int i = 0; i < heroes.size(); i++) {
            League.DraftStep step = m.draft().get(i);
            if (!step.pick() || step.radiant() != predictRadiant) {
                continue;
            }
            List<Integer> prefix = heroes.subList(0, i);
            Map<Integer, Double> dist = new HashMap<>();
            double p = 0;
            double[] w = lineups.size() == 1 ? new double[]{1}
                    : engine.lineupWeights(lineups, keys, null, weFirst, prefix, Map.of(), m.matchId(), prior);
            for (int j = 0; j < lineups.size(); j++) {
                if (w[j] == 0) {
                    continue;
                }
                DraftEngine.Forecast f = engine.forecast(us, lineups.get(j), weFirst, prefix, m.matchId(), false);
                // ранжируем по «следующему пику», как сайт (predictPicks отсекает героев уже выбравших игроков)
                Map<Integer, Double> ranked = new HashMap<>();
                for (int r = 0; r < f.next().size(); r++) {
                    ranked.put(f.next().get(r), f.distribution().getOrDefault(f.next().get(r), 0.0));
                }
                p += w[j] * f.distribution().getOrDefault(step.heroId(), 0.0);
                final double wj = w[j];
                f.distribution().forEach((h, q) -> dist.merge(h, wj * (ranked.containsKey(h) ? 1 + q : q * 1e-3), Double::sum));
            }
            List<Integer> order = dist.entrySet().stream().sorted(Map.Entry.<Integer, Double>comparingByValue().reversed())
                    .map(Map.Entry::getKey).toList();
            int rank = order.indexOf(step.heroId()) + 1;
            int baseRank = DraftBacktest.baseline(ctx, lineups.get(0), prefix).indexOf(step.heroId()) + 1;
            final double ll = -Math.log(Math.max(p, 1e-4));
            final int ph = Math.min(phase, 4);
            add(s, s8, x -> {
                x.picks++;
                x.logLoss += ll;
                x.top1 += rank == 1 ? 1 : 0;
                x.top5 += rank >= 1 && rank <= 5 ? 1 : 0;
                x.top10 += rank >= 1 && rank <= 10 ? 1 : 0;
                x.base5 += baseRank >= 1 && baseRank <= 5 ? 1 : 0;
                x.phasePicks[ph]++;
                x.phaseTop5[ph] += rank >= 1 && rank <= 5 ? 1 : 0;
            });
            phase++;
        }

        // кто на каком герое и позиции — по самому вероятному составу после всех пиков
        double[] w = lineups.size() == 1 ? new double[]{1}
                : engine.lineupWeights(lineups, keys, null, weFirst, heroes, Map.of(), m.matchId(), prior);
        int best = 0;
        for (int j = 1; j < w.length; j++) {
            best = w[j] > w[best] ? j : best;
        }
        Squad chosen = lineups.get(best);
        DraftEngine.Forecast full = engine.forecast(us, chosen, weFirst, heroes, m.matchId(), false);
        Map<Long, Integer> actualPos = new HashMap<>();
        if (actual != null) {
            actual.forEach((pos, id) -> actualPos.put(id, pos));
        }
        for (DraftEngine.Assignment as : full.assignment()) {
            League.Player real = m.side(predictRadiant).stream().filter(pl -> pl.heroId() == as.heroId()).findFirst().orElse(null);
            if (real == null) {
                continue;
            }
            PlayerStats ps = chosen.player(real.accountId());
            boolean right = ps != null && ps.nick().equals(as.player());
            Integer realPos = actualPos.get(real.accountId());
            PlayerStats predicted = chosen.players.stream().filter(pl -> pl.nick().equals(as.player())).findFirst().orElse(null);
            add(s, s8, x -> {
                x.assigned++;
                x.playerRight += right ? 1 : 0;
                if (realPos != null && predicted != null) {
                    x.posAssigned++;
                    x.posRight += chosen.position(predicted) == realPos ? 1 : 0;
                }
            });
        }

        if (v.bans()) {
            Set<Integer> actualBans = m.draft().stream().filter(d -> !d.pick() && d.radiant() == predictRadiant)
                    .map(League.DraftStep::heroId).collect(Collectors.toSet());
            DraftEngine.Forecast f = engine.forecast(us, them, weFirst, List.of(), m.matchId(), true);
            int hits = (int) f.likelyBans().stream().limit(7).filter(actualBans::contains).count();
            int baseHits = (int) teamBans(before, tid).stream().limit(7).filter(actualBans::contains).count();
            int leagueHits = (int) leagueBans(ctx, m).stream().limit(7).filter(actualBans::contains).count();
            add(s, s8, x -> {
                x.bans += actualBans.size();
                x.banHits += hits;
                x.baseBanHits += baseHits;
                x.leagueBanHits += leagueHits;
            });
        }
    }

    private static void add(Stats s, Stats s8, java.util.function.Consumer<Stats> f) {
        f.accept(s);
        if (s8 != null) {
            f.accept(s8);
        }
    }

    private static Squad withLineup(AnalysisContext ctx, Squad team, Map<Integer, Long> lineup, Map<String, Squad> cache)
            throws IOException {
        String key = team.team.teamId() + ":" + lineup;
        Squad s = cache.get(key);
        if (s == null) {
            s = ctx.withLineup(team, lineup);
            cache.put(key, s);
        }
        return s;
    }

    /** Бейзлайн «их привычка»: что команда чаще банила в своих прошлых картах турнира. */
    private static List<Integer> teamBans(List<League.Match> before, long tid) {
        Map<Integer, Integer> count = new HashMap<>();
        for (League.Match x : before) {
            boolean rad = x.isRadiant(tid);
            x.draft().stream().filter(d -> !d.pick() && d.radiant() == rad).forEach(d -> count.merge(d.heroId(), 1, Integer::sum));
        }
        return sortDesc(count);
    }

    /** Бейзлайн «мета лиги»: что чаще банили во всей лиге до этой карты. */
    private static List<Integer> leagueBans(AnalysisContext ctx, League.Match m) {
        Map<Integer, Integer> count = new HashMap<>();
        for (League.Match x : ctx.league.matches) {
            if (x.startTime() < m.startTime()) {
                x.draft().stream().filter(d -> !d.pick()).forEach(d -> count.merge(d.heroId(), 1, Integer::sum));
            }
        }
        return sortDesc(count);
    }

    private static List<Integer> sortDesc(Map<Integer, Integer> count) {
        return count.entrySet().stream().sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed())
                .map(Map.Entry::getKey).toList();
    }

    private static String pct(int a, int b) {
        return b == 0 ? "—" : Md.pct((double) a / b);
    }

    private static void print(String title, Map<String, Stats> stats) {
        StringBuilder out = new StringBuilder("\n## ").append(title).append("\n\n");
        out.append("| Вариант | Сторон | Пиков | Топ-1 | Топ-5 | Топ-10 | log-loss | Бейзлайн топ-5 | Топ-5 по пикам 1–5 | Кто на герое | Позиция | Баны топ-7 | Бейзлайн банов: команда / лига |\n");
        out.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        stats.forEach((name, s) -> {
            StringBuilder phases = new StringBuilder();
            for (int i = 0; i < 5; i++) {
                phases.append(pct(s.phaseTop5[i], s.phasePicks[i])).append(i < 4 ? " / " : "");
            }
            out.append("| ").append(name).append(" | ").append(s.sides).append(" | ").append(s.picks).append(" | ")
                    .append(pct(s.top1, s.picks)).append(" | ").append(pct(s.top5, s.picks)).append(" | ")
                    .append(pct(s.top10, s.picks)).append(" | ")
                    .append(s.picks == 0 ? "—" : String.format("%.3f", s.logLoss / s.picks)).append(" | ")
                    .append(pct(s.base5, s.picks)).append(" | ").append(phases).append(" | ")
                    .append(pct(s.playerRight, s.assigned)).append(" | ").append(pct(s.posRight, s.posAssigned)).append(" | ")
                    .append(s.bans == 0 ? "—" : pct(s.banHits, s.bans)).append(" | ")
                    .append(s.bans == 0 ? "—" : pct(s.baseBanHits, s.bans) + " / " + pct(s.leagueBanHits, s.bans)).append(" |\n");
        });
        System.out.print(out);
    }

    // ---------------------------------------------------------------- конфиг со всеми командами лиги

    /**
     * Основной конфиг + все команды лиги с хотя бы одной картой, где у всех пятерых есть данные (основные или
     * data/backtest/players). Пятёрка и позиции — по последней карте с определимыми позициями; остальные игроки
     * команды — запасные.
     */
    static void writeLeagueConfig(Path configPath, Path dataDir, Path out) throws IOException {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        ObjectNode root = (ObjectNode) yaml.readTree(configPath.toFile());
        AnalysisContext ctx = AnalysisContext.load(configPath, dataDir);
        Set<Long> configTeams = mainTeamIds();
        Set<Long> configPlayers = new HashSet<>();
        ctx.config.players().forEach(p -> configPlayers.add(p.accountId()));
        Set<String> names = new HashSet<>();
        root.path("league").path("group").forEach(t -> names.add(t.path("name").asText()));
        root.path("league").path("playin").forEach(t -> names.add(t.path("name").asText()));
        root.path("league").path("playoff").forEach(t -> names.add(t.path("name").asText()));
        names.add(ctx.config.ourTeam().name());

        Map<Long, String> teamNames = new LinkedHashMap<>();
        Map<Long, Map<Long, String>> nicks = new HashMap<>();
        for (League.Match m : ctx.league.matches) {
            for (boolean rad : new boolean[]{true, false}) {
                long tid = rad ? m.radiantTeamId() : m.direTeamId();
                if (tid == 0 || configTeams.contains(tid)) {
                    continue;
                }
                teamNames.putIfAbsent(tid, rad ? m.radiantName() : m.direName());
                m.side(rad).forEach(p -> nicks.computeIfAbsent(tid, k -> new HashMap<>())
                        .putIfAbsent(p.accountId(), p.personaName().isBlank() ? "p" + p.accountId() : p.personaName()));
            }
        }
        // игрок, сыгравший за несколько команд (замена), — в той, где у него больше карт
        Map<Long, Map<Long, Integer>> mapsFor = new HashMap<>();
        for (League.Match m : ctx.league.matches) {
            for (boolean rad : new boolean[]{true, false}) {
                long tid = rad ? m.radiantTeamId() : m.direTeamId();
                m.side(rad).forEach(p -> mapsFor.computeIfAbsent(p.accountId(), k -> new HashMap<>()).merge(tid, 1, Integer::sum));
            }
        }
        Map<Long, Long> owner = new HashMap<>();
        mapsFor.forEach((id, teams) -> owner.put(id, teams.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey()));
        ArrayNode group = (ArrayNode) root.path("league").path("group");
        ArrayNode players = (ArrayNode) root.path("players");
        int teams = 0, skipped = 0;
        for (var e : teamNames.entrySet()) {
            long tid = e.getKey();
            Set<Long> ids = new HashSet<>(nicks.get(tid).keySet());
            ids.removeIf(id -> id <= 0 || configPlayers.contains(id) || owner.get(id) != tid || !hasData(dataDir, id));
            List<TeamLineups.Seen> seen = TeamLineups.seen(ctx.league.of(tid), tid, ids);
            if (seen.isEmpty()) {
                skipped++;
                continue;
            }
            Map<Integer, Long> lineup = seen.get(0).lineup();
            String name = e.getValue() == null || e.getValue().isBlank() || e.getValue().equals("?") ? "Team " + tid : e.getValue();
            while (names.contains(name)) {
                name = name + " #" + tid;
            }
            names.add(name);
            ObjectNode t = group.addObject();
            t.put("name", name);
            t.put("team_id", tid);
            Map<Long, Integer> posOf = new HashMap<>();
            lineup.forEach((pos, id) -> posOf.put(id, pos));
            // запасным — самая частая позиция в картах команды
            Map<Long, Map<Integer, Integer>> posCount = new HashMap<>();
            for (TeamLineups.Seen s : seen) {
                s.lineup().forEach((pos, id) -> posCount.computeIfAbsent(id, k -> new HashMap<>()).merge(pos, s.maps(), Integer::sum));
            }
            Set<String> teamNicks = new HashSet<>();
            for (long id : ids) {
                ObjectNode p = players.addObject();
                p.put("account_id", id);
                String nick = nicks.get(tid).get(id).replace("\"", "'").trim();
                p.put("nick", teamNicks.add(nick) ? nick : nick + " #" + id);
                p.put("team", "opp");
                p.put("team_name", name);
                Integer pos = posOf.get(id);
                if (pos == null) {
                    pos = posCount.getOrDefault(id, Map.of()).entrySet().stream().max(Map.Entry.comparingByValue())
                            .map(Map.Entry::getKey).orElse(null);
                    p.put("reserve", true);
                }
                if (pos != null) {
                    p.put("position", pos);
                }
            }
            teams++;
        }
        Files.createDirectories(out.getParent());
        yaml.writeValue(out.toFile(), root);
        System.out.printf("Записан %s: добавлено команд %d, пропущено (нет карты с данными всех пятерых) %d%n", out, teams, skipped);
    }

    private static boolean hasData(Path dataDir, long id) {
        Path dir = dataDir.resolve("backtest/players").resolve(String.valueOf(id));
        return Files.isRegularFile(dir.resolve("profile.json")) && Files.isRegularFile(dir.resolve("matches.json"))
                && Files.isRegularFile(dir.resolve("rankings.json"));
    }
}
