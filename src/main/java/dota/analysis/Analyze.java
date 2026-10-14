package dota.analysis;

import dota.fetch.PlayerConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Строит Markdown-отчёты в analysis/ из data/: по нашим игрокам и соперникам, по мете патча, по турниру.
 * Файлы *_plan.md пишутся вручную и генератором не трогаются.
 * <pre>
 *   --config &lt;path&gt;   по умолчанию config/players.yaml
 *   --data &lt;dir&gt;      по умолчанию data
 *   --out &lt;dir&gt;       по умолчанию analysis
 * </pre>
 */
public class Analyze {

    static final int TOP = 15;
    /** Минимум игр пары в public-выборке, чтобы показывать синергию/контрпик. */
    static final long MIN_PAIR_GAMES = 200;

    private final Meta meta;
    private final List<PlayerStats> players;
    private final Map<PlayerStats, Integer> pos = new LinkedHashMap<>();
    private final Map<PlayerStats, Boolean> posInferred = new HashMap<>();
    private final PlayerConfig.TeamInfo team;
    private final boolean ours;
    private final League league;
    private final int teamFrom, teamTo;
    private final String today;

    Analyze(Meta meta, List<PlayerStats> players, PlayerConfig.TeamInfo team, boolean ours, League league) {
        this.meta = meta;
        this.players = players;
        this.team = team;
        this.ours = ours;
        this.league = league;
        assignPositions();
        int[] brackets = players.stream().mapToInt(PlayerStats::bracket).sorted().toArray();
        int median = brackets[brackets.length / 2];
        this.teamFrom = Math.max(1, median - 1);
        this.teamTo = Math.min(8, median + 1);
        this.today = LocalDate.now(ZoneOffset.UTC).toString();
    }

    public static void main(String[] args) throws IOException {
        Path configPath = Path.of("config/players.yaml");
        Path dataDir = Path.of("data");
        Path out = Path.of("analysis");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config" -> configPath = Path.of(args[++i]);
                case "--data" -> dataDir = Path.of(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
            }
        }

        PlayerConfig config = PlayerConfig.load(configPath);
        Meta meta = Meta.load(dataDir);
        League league = config.league() == null || config.league().id() == null ? null
                : League.load(dataDir, config.league());
        Instant now = Instant.now();

        Analyze ourSquad = new Analyze(meta, loadPlayers(dataDir, config, null, meta, now), config.ourTeam(), true, league);
        List<Analyze> opponents = new ArrayList<>();
        if (config.league() != null) {
            for (PlayerConfig.TeamInfo t : config.league().groupOrEmpty()) {
                List<PlayerStats> squad = loadPlayers(dataDir, config, t.name(), meta, now);
                if (!squad.isEmpty()) {
                    opponents.add(new Analyze(meta, squad, t, false, league));
                }
            }
        }

        ourSquad.writeAll(out, opponents);
        for (Analyze opp : opponents) {
            opp.writeOpponent(out.resolve("opponents").resolve(opp.slug()), ourSquad);
        }
        if (league != null) {
            write(out.resolve("league/group.md"), groupReport(meta, league, config));
        }
        System.out.println("Analysis written to " + out.toAbsolutePath());
    }

    /** teamName == null — наши игроки, иначе соперники с этим team_name. */
    private static List<PlayerStats> loadPlayers(Path dataDir, PlayerConfig config, String teamName, Meta meta,
                                                 Instant now) throws IOException {
        List<PlayerConfig.Player> squad = config.players().stream()
                .filter(p -> teamName == null ? p.team() == PlayerConfig.Team.OUR
                        : p.team() == PlayerConfig.Team.OPP && teamName.equals(p.teamName()))
                .toList();
        Set<Long> ids = squad.stream().map(PlayerConfig.Player::accountId).collect(Collectors.toSet());
        List<PlayerStats> players = new ArrayList<>();
        for (PlayerConfig.Player p : squad) {
            players.add(PlayerStats.load(dataDir, p, ids, meta.patchStart, now));
        }
        return players;
    }

    void writeAll(Path out, List<Analyze> opponents) throws IOException {
        write(out.resolve("README.md"), readme(opponents));
        write(out.resolve("team/roster.md"), roster());
        write(out.resolve("team/draft_data.md"), draftData());
        if (league != null && team != null && team.teamId() != null) {
            write(out.resolve("team/tournament.md"), tournament());
        }
        write(out.resolve("meta/patch_" + meta.patch + ".md"), metaReport());
        for (PlayerStats p : players) {
            write(out.resolve("players").resolve(playerFile(p)), playerReport(p));
        }
    }

    /** Соперник: analysis/opponents/{slug}/ — та же структура, что у нас, плюс угрозы и как против них играть. */
    void writeOpponent(Path dir, Analyze ourSquad) throws IOException {
        write(dir.resolve("README.md"), opponentReadme());
        write(dir.resolve("team/roster.md"), roster());
        write(dir.resolve("team/threats.md"), threats(ourSquad));
        if (league != null && team.teamId() != null) {
            write(dir.resolve("team/tournament.md"), tournament());
        }
        for (PlayerStats p : players) {
            write(dir.resolve("players").resolve(playerFile(p)), playerReport(p));
        }
    }

    String slug() {
        return team.name().replaceAll("[^\\p{L}\\p{N}]+", "_");
    }

    // ---------------------------------------------------------------- позиции

    /** Позиции из конфига; пустые раздаём оставшимся по доле игр на героях этих позиций (перебор). */
    private void assignPositions() {
        List<Integer> free = new ArrayList<>(List.of(1, 2, 3, 4, 5));
        List<PlayerStats> unassigned = new ArrayList<>();
        for (PlayerStats p : players) {
            Integer configured = p.config.position();
            if (configured != null && free.remove(configured)) {
                pos.put(p, configured);
                posInferred.put(p, false);
            } else {
                unassigned.add(p);
            }
        }
        if (unassigned.isEmpty()) {
            return;
        }
        int[] best = null;
        double bestScore = -1;
        for (int[] perm : permutations(free.stream().mapToInt(Integer::intValue).toArray(), unassigned.size())) {
            double score = 0;
            for (int i = 0; i < perm.length; i++) {
                score += unassigned.get(i).yearShareOnPos(meta, perm[i]);
            }
            if (score > bestScore) {
                bestScore = score;
                best = perm;
            }
        }
        for (int i = 0; i < unassigned.size(); i++) {
            pos.put(unassigned.get(i), best == null ? 0 : best[i]);
            posInferred.put(unassigned.get(i), true);
        }
    }

    private static List<int[]> permutations(int[] items, int k) {
        List<int[]> result = new ArrayList<>();
        permute(items, k, new int[k], new boolean[items.length], 0, result);
        return result;
    }

    private static void permute(int[] items, int k, int[] cur, boolean[] used, int depth, List<int[]> out) {
        if (depth == k) {
            out.add(cur.clone());
            return;
        }
        for (int i = 0; i < items.length; i++) {
            if (!used[i]) {
                used[i] = true;
                cur[depth] = items[i];
                permute(items, k, cur, used, depth + 1, out);
                used[i] = false;
            }
        }
    }

    private List<PlayerStats> byPosition() {
        return players.stream().sorted(Comparator.comparingInt(pos::get)).toList();
    }

    private String playerFile(PlayerStats p) {
        return "pos" + pos.get(p) + "_" + p.nick() + ".md";
    }

    // ---------------------------------------------------------------- оценки

    private int[] bracketRange(PlayerStats p) {
        int b = p.bracket();
        return new int[]{Math.max(1, b - 1), Math.min(8, b + 1)};
    }

    private double metaStrength(int hero, int position, int from, int to) {
        return Scoring.metaStrength(meta.winrate(hero, from, to), meta.posWinrate(hero, position), meta.proContest(hero));
    }

    private static String label(double wr) {
        if (Double.isNaN(wr)) {
            return "";
        }
        if (wr >= Meta.STRONG_WR) {
            return "▲ сильный";
        }
        if (wr <= Meta.WEAK_WR) {
            return "▼ слабый";
        }
        return "";
    }

    record Fit(int hero, double fit, double meta, double skill) {
    }

    /** Герои, которых в патче играют на позиции (≥15% разобранных игр), по пригодности для игрока. */
    List<Fit> patchFit(PlayerStats p, int position, int limit) {
        int[] r = bracketRange(p);
        return meta.heroes.keySet().stream()
                .filter(h -> meta.posTotal(h) >= 20 && meta.posShare(h, position) >= 0.15)
                .map(h -> {
                    double m = metaStrength(h, position, r[0], r[1]);
                    double s = p.skillOn(h);
                    return new Fit(h, Scoring.patchFit(m, s), m, s);
                })
                .sorted(Comparator.comparingDouble(Fit::fit).reversed())
                .limit(limit)
                .toList();
    }

    /** Сильные/слабые на позиции в патче по винрейту на диапазоне рангов; пикрейт ≥ 1% матчей. */
    private List<Integer> standouts(int position, int from, int to, boolean strong, int limit) {
        Comparator<Integer> byWr = Comparator.comparingDouble(h -> meta.winrate(h, from, to));
        return meta.heroes.keySet().stream()
                .filter(h -> meta.posTotal(h) >= 20 && meta.posShare(h, position) >= 0.25)
                .filter(h -> meta.pickRate(h, from, to) >= 0.01)
                .filter(h -> strong ? meta.winrate(h, from, to) >= Meta.STRONG_WR : meta.winrate(h, from, to) <= Meta.WEAK_WR)
                .sorted(strong ? byWr.reversed() : byWr)
                .limit(limit)
                .toList();
    }

    private String rangeName(int from, int to) {
        return from == to ? Meta.BRACKET_RU[from] : Meta.BRACKET_RU[from] + "–" + Meta.BRACKET_RU[to];
    }

    // ---------------------------------------------------------------- отчёты

    private String readme(List<Analyze> opponents) {
        Md md = new Md().h1("Анализ: " + teamName());
        md.line("Сгенерировано %s из `data/` (патч **%s**, начало %s). Пересобрать: `./gradlew analyze`.",
                today, meta.patch, meta.patchStart.toString().substring(0, 10));
        md.h2("Что где лежит");
        md.line("| Файл | Что внутри |").line("|---|---|");
        md.line("| [team/draft_plan.md](team/draft_plan.md) | **План драфта**: выводы и рекомендации (пишется вручную на основе файлов ниже) |");
        md.line("| [team/roster.md](team/roster.md) | Состав, позиции, ранги, на каких позициях кто реально играет, сыгранность |");
        md.line("| [team/draft_data.md](team/draft_data.md) | Цифры для драфта: пулы по позициям, связки внутри команды, неудобные герои, кандидаты в баны |");
        if (league != null) {
            md.line("| [team/tournament.md](team/tournament.md) | Наши турнирные матчи: драфты, кто что играл, что банят против нас |");
            md.line("| [league/group.md](league/group.md) | Группа: результаты и драфты всех матчей группы |");
        }
        for (Analyze opp : opponents) {
            md.line("| [opponents/%s/](opponents/%s/README.md) | Соперник %s: состав, угрозы, турнирные драфты, игроки |",
                    opp.slug(), opp.slug(), opp.teamName());
        }
        md.line("| [meta/patch_%s.md](meta/patch_%s.md) | Мета патча: сильные/слабые по позициям, про-сцена (CM), лучшие связки и контрпики |", meta.patch, meta.patch);
        for (PlayerStats p : byPosition()) {
            md.line("| [players/%s](players/%s) | Позиция %d, %s: лучшие герои и герои под патч |",
                    playerFile(p), playerFile(p), pos.get(p), p.nick());
        }
        md.h2("Как считаются оценки");
        md.line("- **Умение на герое (0–100)**: 40% — место среди всех игроков героя (OpenDota rankings, percent_rank);")
                .line("  35% — винрейт с весом по давности (игра годичной давности весит ½), сглаженный к 50% весом 10 игр;")
                .line("  25% — опыт (40 «свежих» игр = максимум). Турбо не учитывается.")
                .line("- **Сила в мете (0–1)**: 60% — винрейт героя в пабах на ранге игрока ±1; 20% — винрейт на этой")
                .line("  позиции в разобранных матчах патча (в основном про и высокий MMR); 20% — доля про-матчей с пиком/баном.")
                .line("- **Пригодность в патче (0–100)** = поровну сила в мете и умение игрока.")
                .line("- **▲ сильный / ▼ слабый** — винрейт в пабах на нужном ранге ≥ %.0f%% / ≤ %.0f%%.", 100 * Meta.STRONG_WR, 100 * Meta.WEAK_WR)
                .line("- **Синергия пары** — винрейт пары в одной команде минус среднее базовых винрейтов героев;")
                .line("  **контрпик** — винрейт героя против героя минус ожидаемый. Выборка: ~250 тыс. публичных матчей")
                .line("  Legend+ текущего патча за последние ~15 часов (`data/meta/%s/_sample.json`).", meta.patch);
        md.h2("Ограничения");
        md.line("- Позиции 1–5 в OpenDota напрямую нет: позиция героя оценена по разобранным матчам патча")
                .line("  (4/5 — два последних по GPM в команде, 1/2/3 — по линии). Для редких героев может ошибаться.")
                .line("- Детальная статистика (линия, предметы) есть только у разобранных матчей игроков.")
                .line("- Патч берётся из справочника OpenDota; если он отстаёт от игры, мета будет за прошлый патч.");
        return md.toString();
    }

    private String teamName() {
        if (team == null || team.name() == null) {
            return "наша команда";
        }
        return team.name() + (team.organization() == null ? "" : " (" + team.organization() + ")");
    }

    private String roster() {
        Md md = new Md().h1("Состав: " + teamName());
        md.line("Данные на %s, патч %s. Турбо не учитывается.", today, meta.patch);
        Md.Table t = md.table("Поз.", "Игрок", "Steam-ник", "account_id", "Ранг", "MMR (оценка)",
                "Матчей всего / год / патч", "WR в патче", "Captains Mode", "Роль в команде");
        for (PlayerStats p : byPosition()) {
            t.row(pos.get(p) + (posInferred.get(p) ? "*" : ""), "[" + p.nick() + "](../players/" + playerFile(p) + ")",
                    p.personaName, p.config.accountId(), Meta.bracketName(p.rankTier), Md.num(p.computedMmr),
                    p.totalGames + " / " + p.yearGames + " / " + p.patchGames,
                    p.patchGames == 0 ? "—" : Md.pct((double) p.patchWins / p.patchGames),
                    p.cmGames == 0 ? "0" : p.cmGames + " (" + Md.pct((double) p.cmWins / p.cmGames) + ")",
                    p.config.note() == null ? "" : p.config.note());
        }
        t.end();
        if (posInferred.containsValue(true)) {
            md.blank().line("\\* позиция не указана в конфиге и определена по данным (доля игр на героях этой позиции).");
        }

        md.h2("На каких позициях реально играют (пабы за последний год)");
        md.line("Доля игр на героях, которых в патче играют на позиции N (по профилю героя, а не по факту матча).").blank();
        Md.Table share = md.table("Игрок", "Поз. в команде", "1", "2", "3", "4", "5", "Линии в разобранных матчах (safe/mid/off/лес)");
        for (PlayerStats p : byPosition()) {
            share.row(p.nick(), pos.get(p),
                    Md.pct(p.yearShareOnPos(meta, 1)), Md.pct(p.yearShareOnPos(meta, 2)), Md.pct(p.yearShareOnPos(meta, 3)),
                    Md.pct(p.yearShareOnPos(meta, 4)), Md.pct(p.yearShareOnPos(meta, 5)),
                    p.yearLaneRole[1] + "/" + p.yearLaneRole[2] + "/" + p.yearLaneRole[3] + "/" + p.yearLaneRole[4]);
        }
        share.end();

        md.h2("Сыгранность: матчей в одной команде (WR)");
        List<PlayerStats> ordered = byPosition();
        String[] headers = new String[ordered.size() + 1];
        headers[0] = "";
        for (int i = 0; i < ordered.size(); i++) {
            headers[i + 1] = ordered.get(i).nick();
        }
        Md.Table m = md.table(headers);
        for (PlayerStats a : ordered) {
            Object[] row = new Object[ordered.size() + 1];
            row[0] = a.nick();
            for (int i = 0; i < ordered.size(); i++) {
                PlayerStats b = ordered.get(i);
                int g = a.withTeammate.getOrDefault(b.config.accountId(), 0);
                int w = a.winsWithTeammate.getOrDefault(b.config.accountId(), 0);
                row[i + 1] = a == b ? "—" : g == 0 ? "0" : g + " (" + Md.pct((double) w / g) + ")";
            }
            m.row(row);
        }
        m.end();
        return md.toString();
    }

    private String playerReport(PlayerStats p) {
        int position = pos.get(p);
        int[] r = bracketRange(p);
        Md md = new Md().h1("Позиция " + position + " — " + p.nick());
        md.line("- Steam: %s, account_id %d, [OpenDota](https://www.opendota.com/players/%d), [Dotabuff](https://ru.dotabuff.com/players/%d)",
                p.personaName, p.config.accountId(), p.config.accountId(), p.config.accountId());
        md.line("- Ранг: %s, оценка MMR %s", Meta.bracketName(p.rankTier), Md.num(p.computedMmr));
        md.line("- Матчей (без турбо): всего %d, за год %d, в патче %s — %d (WR %s); Captains Mode: %d",
                p.totalGames, p.yearGames, meta.patch, p.patchGames,
                p.patchGames == 0 ? "—" : Md.pct((double) p.patchWins / p.patchGames), p.cmGames);
        if (p.config.note() != null) {
            md.line("- Роль: %s", p.config.note());
        }
        md.line("- Мета для этого игрока считается на рангах **%s**.", rangeName(r[0], r[1]));

        md.h2("Топ-" + TOP + " героев, на которых играет лучше всего");
        md.line("Не просто винрейт или число игр — см. «Как считаются оценки» в [README](../README.md). Позиции героя — как его играют в патче.").blank();
        Md.Table best = md.table("#", "Герой", "Умение", "Игр всего / год / патч", "WR всего", "WR за год",
                "Ранг на герое", "KDA", "Поз. героя", "В патче (" + rangeName(r[0], r[1]) + ")");
        int i = 1;
        for (PlayerStats.HeroLine h : p.bestHeroes(TOP)) {
            double wr = meta.winrate(h.heroId, r[0], r[1]);
            best.row(i++, meta.name(h.heroId), Md.num(h.skill),
                    h.games + " / " + h.yearGames + " / " + h.patchGames, Md.pct(h.winrate()),
                    h.yearGames == 0 ? "—" : Md.pct((double) h.yearWins / h.yearGames),
                    topPercent(h.percentRank),
                    Md.dec(h.kda()), meta.positions(h.heroId), Md.pct(wr) + " " + label(wr));
        }
        best.end();

        List<Fit> fits = patchFit(p, position, TOP);
        md.h2("Топ-" + TOP + " героев под патч " + meta.patch + " на позиции " + position);
        md.line("Герои, которых в патче играют на позиции %d (≥15%% игр), по пригодности = сила в мете + умение игрока.", position).blank();
        Md.Table fit = md.table("#", "Герой", "Пригодность", "Сила в мете", "WR на ранге", "WR на поз. (про/high)",
                "Про пик+бан", "Умение игрока", "Игр у игрока", "Отметка");
        i = 1;
        for (Fit f : fits) {
            PlayerStats.HeroLine h = p.heroes.get(f.hero());
            double wr = meta.winrate(f.hero(), r[0], r[1]);
            String mark = label(wr);
            if (position == 3 && meta.posShare(f.hero(), 1) >= 0.2) {
                mark = (mark + " может в керри").trim();
            }
            if (h == null || h.games < 3) {
                mark = (mark + " нет опыта").trim();
            }
            fit.row(i++, meta.name(f.hero()), Md.num(f.fit()), Md.dec(f.meta()), Md.pct(wr),
                    Md.pct(meta.posWinrate(f.hero(), position)), Md.pct(meta.proContest(f.hero())),
                    Md.num(f.skill()), h == null ? 0 : h.games, mark);
        }
        fit.end();

        md.h2("Выделяются в патче на позиции " + position + " (" + rangeName(r[0], r[1]) + ")");
        md.line("**▲ Сильные:** %s", heroList(standouts(position, r[0], r[1], true, 10), r));
        md.blank().line("**▼ Слабые:** %s", heroList(standouts(position, r[0], r[1], false, 8), r));

        List<String> weakInPool = p.bestHeroes(TOP).stream()
                .filter(h -> meta.winrate(h.heroId, r[0], r[1]) <= Meta.WEAK_WR)
                .map(h -> meta.name(h.heroId) + " (" + Md.pct(meta.winrate(h.heroId, r[0], r[1])) + ")")
                .toList();
        md.h2("Осторожно: из личного топа, но слабы в патче");
        md.line(weakInPool.isEmpty() ? "Нет." : String.join(", ", weakInPool));
        return md.toString();
    }

    /** percent_rank 0.995 → "топ <1%", 0.95 → "топ 5%". */
    static String topPercent(Double percentRank) {
        if (percentRank == null) {
            return "—";
        }
        double top = 100 * (1 - percentRank);
        return top < 1 ? "топ <1%" : "топ " + Md.num(top) + "%";
    }

    private String heroList(List<Integer> heroes, int[] r) {
        if (heroes.isEmpty()) {
            return "нет";
        }
        return heroes.stream()
                .map(h -> meta.name(h) + " " + Md.pct(meta.winrate(h, r[0], r[1])))
                .collect(Collectors.joining(", "));
    }

    private String metaReport() {
        Md md = new Md().h1("Мета патча " + meta.patch);
        md.line("Данные на %s. Винрейты в пабах — на рангах **%s** (медиана нашей команды ±1). Про-сцена — %d матчей в выборке OpenDota.",
                today, rangeName(teamFrom, teamTo), meta.proMatches);

        for (int position = 1; position <= 5; position++) {
            final int ps = position;
            md.h2("Позиция " + position);
            List<Integer> top = meta.heroes.keySet().stream()
                    .filter(h -> meta.posTotal(h) >= 20 && meta.posShare(h, ps) >= 0.25)
                    .filter(h -> meta.pickRate(h, teamFrom, teamTo) >= 0.01)
                    .sorted(Comparator.comparingDouble((Integer h) -> metaStrength(h, ps, teamFrom, teamTo)).reversed())
                    .limit(12).toList();
            Md.Table t = md.table("#", "Герой", "Сила в мете", "WR в пабах", "Пикрейт", "WR на поз. (про/high)",
                    "Про пик+бан", "Отметка");
            int i = 1;
            for (int h : top) {
                double wr = meta.winrate(h, teamFrom, teamTo);
                t.row(i++, meta.name(h), Md.dec(metaStrength(h, ps, teamFrom, teamTo)), Md.pct(wr),
                        Md.pct(meta.pickRate(h, teamFrom, teamTo)), Md.pct(meta.posWinrate(h, ps)),
                        Md.pct(meta.proContest(h)), label(wr));
            }
            t.end();
            md.blank().line("**▼ Слабые на позиции:** %s",
                    heroList(standouts(position, teamFrom, teamTo, false, 8), new int[]{teamFrom, teamTo}));
        }

        md.h2("Про-сцена: самые оспариваемые герои (пик + бан)");
        md.line("Для Captains Mode: этих героев чаще всего банят или берут первыми.").blank();
        Md.Table pro = md.table("#", "Герой", "Пик+бан", "Пики", "Баны", "WR в про", "Позиции");
        int i = 1;
        for (int h : meta.heroes.keySet().stream()
                .sorted(Comparator.comparingDouble(meta::proContest).reversed()).limit(20).toList()) {
            Meta.HeroStats s = meta.stats.get(h);
            pro.row(i++, meta.name(h), Md.pct(meta.proContest(h)), s.proPick(), s.proBan(),
                    s.proPick() == 0 ? "—" : Md.pct((double) s.proWin() / s.proPick()), meta.positions(h));
        }
        pro.end();

        md.h2("Лучшие связки (в одной команде)");
        md.line("Синергия = винрейт пары − среднее базовых винрейтов героев. Минимум 400 игр пары.").blank();
        Md.Table syn = md.table("#", "Связка", "Синергия", "WR пары", "Игр");
        i = 1;
        for (var e : meta.synergy.entrySet().stream()
                .filter(e -> e.getValue().games() >= 400)
                .sorted(Comparator.comparingDouble((Map.Entry<Meta.Pair, Meta.PairStats> e) ->
                        meta.synergyLift(e.getKey().a(), e.getKey().b(), 400)).reversed())
                .limit(30).toList()) {
            syn.row(i++, meta.name(e.getKey().a()) + " + " + meta.name(e.getKey().b()),
                    Md.signedPct(meta.synergyLift(e.getKey().a(), e.getKey().b(), 400)),
                    Md.pct(e.getValue().winrate()), e.getValue().games());
        }
        syn.end();

        md.h2("Самые жёсткие контрпики");
        md.line("Преимущество = винрейт героя против героя − ожидаемый из их базовых винрейтов. Минимум 400 игр.").blank();
        Md.Table cnt = md.table("#", "Герой", "контрит", "Преимущество", "WR", "Игр");
        i = 1;
        for (var e : meta.counters.entrySet().stream()
                .filter(e -> e.getValue().games() >= 400)
                .sorted(Comparator.comparingDouble((Map.Entry<Meta.Pair, Meta.PairStats> e) ->
                        meta.counterAdvantage(e.getKey().a(), e.getKey().b(), 400)).reversed())
                .limit(30).toList()) {
            cnt.row(i++, meta.name(e.getKey().a()), meta.name(e.getKey().b()),
                    Md.signedPct(meta.counterAdvantage(e.getKey().a(), e.getKey().b(), 400)),
                    Md.pct(e.getValue().winrate()), e.getValue().games());
        }
        cnt.end();
        return md.toString();
    }

    private String draftData() {
        Md md = new Md().h1("Данные для драфта: " + teamName());
        md.line("Сгенерировано %s, патч %s. Выводы — в [draft_plan.md](draft_plan.md).", today, meta.patch);

        md.h2("Пулы по позициям (топ-6 по пригодности в патче)");
        Map<PlayerStats, List<Fit>> pools = new LinkedHashMap<>();
        for (PlayerStats p : byPosition()) {
            pools.put(p, patchFit(p, pos.get(p), 10));
        }
        Md.Table pool = md.table("Поз.", "Игрок", "Кандидаты (пригодность; ▲ — сильный в патче)");
        for (var e : pools.entrySet()) {
            int[] r = bracketRange(e.getKey());
            pool.row(pos.get(e.getKey()), e.getKey().nick(), e.getValue().stream().limit(6)
                    .map(f -> meta.name(f.hero()) + " " + Md.num(f.fit())
                            + (meta.winrate(f.hero(), r[0], r[1]) >= Meta.STRONG_WR ? " ▲" : ""))
                    .collect(Collectors.joining(", ")));
        }
        pool.end();

        md.h2("Связки внутри наших пулов");
        md.line("Пары героев из топ-10 пулов двух разных игроков (только герои, на которых игрок сыграл ≥ 3 игр),")
                .line("синергия ≥ +2%% (минимум %d игр пары в выборке).", MIN_PAIR_GAMES).blank();
        record Combo(PlayerStats a, int ha, PlayerStats b, int hb, double lift, Meta.PairStats s) {
        }
        List<Combo> combos = new ArrayList<>();
        List<PlayerStats> order = byPosition();
        for (int x = 0; x < order.size(); x++) {
            for (int y = x + 1; y < order.size(); y++) {
                for (Fit fa : pools.get(order.get(x))) {
                    for (Fit fb : pools.get(order.get(y))) {
                        if (fa.hero() == fb.hero() || fa.skill() == 0 || fb.skill() == 0) {
                            continue;
                        }
                        double lift = meta.synergyLift(fa.hero(), fb.hero(), MIN_PAIR_GAMES);
                        if (lift >= 0.02) {
                            combos.add(new Combo(order.get(x), fa.hero(), order.get(y), fb.hero(), lift,
                                    meta.synergyStats(fa.hero(), fb.hero())));
                        }
                    }
                }
            }
        }
        combos.sort(Comparator.comparingDouble(Combo::lift).reversed());
        // одна и та же пара героев может встретиться у разных игроков — оставляем первую
        Set<Meta.Pair> shown = new HashSet<>();
        combos.removeIf(c -> !shown.add(new Meta.Pair(Math.min(c.ha(), c.hb()), Math.max(c.ha(), c.hb()))));
        Md.Table ct = md.table("#", "Связка", "Игроки", "Синергия", "WR пары", "Игр");
        int i = 1;
        for (Combo c : combos.stream().limit(25).toList()) {
            ct.row(i++, meta.name(c.ha()) + " + " + meta.name(c.hb()), c.a().nick() + " + " + c.b().nick(),
                    Md.signedPct(c.lift()), Md.pct(c.s().winrate()), c.s().games());
        }
        ct.end();

        // Неудобные герои: по всем нашим игрокам за 2 года, общий матч считается один раз
        Map<Integer, int[]> vs = new HashMap<>();
        Set<String> seen = new HashSet<>();
        for (PlayerStats p : players) {
            for (PlayerStats.Enemy e : p.enemies) {
                if (seen.add(e.matchId() + ":" + e.heroId())) {
                    int[] acc = vs.computeIfAbsent(e.heroId(), k -> new int[2]);
                    acc[0]++;
                    acc[1] += e.win() ? 1 : 0;
                }
            }
        }
        int totalG = vs.values().stream().mapToInt(a -> a[0]).sum();
        int totalW = vs.values().stream().mapToInt(a -> a[1]).sum();
        double avg = totalG == 0 ? 0.5 : (double) totalW / totalG;
        Map<Integer, Double> vsWr = new HashMap<>();
        vs.forEach((h, a) -> vsWr.put(h, Scoring.shrink(a[1], a[0], avg, 30)));

        md.h2("Против кого мы проигрываем чаще всего");
        md.line("Все матчи 5 игроков за 2 года (без турбо), герой во вражеской команде. Винрейт сглажен к нашему среднему (%s) весом 30 игр.", Md.pct(avg)).blank();
        Md.Table nem = md.table("#", "Герой соперника", "Наш WR против", "Игр", "WR героя в пабах (" + rangeName(teamFrom, teamTo) + ")");
        i = 1;
        for (int h : vs.keySet().stream().filter(h -> vs.get(h)[0] >= 40)
                .sorted(Comparator.comparingDouble(vsWr::get)).limit(15).toList()) {
            nem.row(i++, meta.name(h), Md.pct(vsWr.get(h)), vs.get(h)[0], Md.pct(meta.winrate(h, teamFrom, teamTo)));
        }
        nem.end();
        md.blank().line("**Против кого играем лучше всего:** %s", vs.keySet().stream().filter(h -> vs.get(h)[0] >= 40)
                .sorted(Comparator.comparingDouble((Integer h) -> vsWr.get(h)).reversed()).limit(10)
                .map(h -> meta.name(h) + " " + Md.pct(vsWr.get(h))).collect(Collectors.joining(", ")));

        md.h2("Кандидаты в баны");
        md.line("Оценка = половина «насколько мы проигрываем против героя» (если против него ≥ 40 игр)")
                .line("+ половина «сила в мете на нашем уровне». Герои из топ-6 наших пулов сюда не попадают.").blank();
        Set<Integer> ourCore = pools.values().stream().flatMap(l -> l.stream().limit(6)).map(Fit::hero).collect(Collectors.toSet());
        Map<Integer, Double> banScore = new HashMap<>();
        for (int h : meta.heroes.keySet()) {
            if (ourCore.contains(h)) {
                continue;
            }
            double against = vs.containsKey(h) && vs.get(h)[0] >= 40 ? Scoring.clamp01((avg - vsWr.get(h)) / 0.08) : 0;
            int mp = Math.max(1, meta.mainPos(h));
            banScore.put(h, 0.5 * against + 0.5 * metaStrength(h, mp, teamFrom, teamTo));
        }
        Md.Table bans = md.table("#", "Герой", "Оценка", "Наш WR против", "WR в пабах", "Про пик+бан", "Позиции");
        i = 1;
        for (int h : banScore.keySet().stream().sorted(Comparator.comparingDouble(banScore::get).reversed()).limit(15).toList()) {
            bans.row(i++, meta.name(h), Md.dec(banScore.get(h)), vs.containsKey(h) ? Md.pct(vsWr.get(h)) + " (" + vs.get(h)[0] + ")" : "—",
                    Md.pct(meta.winrate(h, teamFrom, teamTo)), Md.pct(meta.proContest(h)), meta.positions(h));
        }
        bans.end();

        md.h2("Чем контрят наших лучших героев");
        md.line("Для топ-3 каждого пула — герои с наибольшим преимуществом против него (минимум %d игр).", MIN_PAIR_GAMES).blank();
        Md.Table th = md.table("Игрок", "Наш герой", "Опасные против него");
        for (var e : pools.entrySet()) {
            for (Fit f : e.getValue().stream().limit(3).toList()) {
                String threats = meta.heroes.keySet().stream()
                        .filter(h -> !Double.isNaN(meta.counterAdvantage(h, f.hero(), MIN_PAIR_GAMES)))
                        .sorted(Comparator.comparingDouble((Integer h) -> meta.counterAdvantage(h, f.hero(), MIN_PAIR_GAMES)).reversed())
                        .limit(4)
                        .map(h -> meta.name(h) + " " + Md.signedPct(meta.counterAdvantage(h, f.hero(), MIN_PAIR_GAMES)))
                        .collect(Collectors.joining(", "));
                th.row(e.getKey().nick(), meta.name(f.hero()), threats);
            }
        }
        th.end();
        return md.toString();
    }

    // ---------------------------------------------------------------- турнир и соперники

    private String nickOrPersona(League.Player p) {
        return players.stream().filter(s -> s.config.accountId() == p.accountId()).findFirst()
                .map(PlayerStats::nick).orElse(p.personaName().isEmpty() ? String.valueOf(p.accountId()) : p.personaName());
    }

    /** Турнирные матчи команды (team.teamId): драфты, герои игроков, сводка по пикам и банам. */
    private String tournament() {
        long id = team.teamId();
        List<League.Match> matches = league.of(id);
        Md md = new Md().h1("Турнир: " + teamName());
        md.line("Лига OpenDota %d, матчи с начала сезона. Сыграно карт: %d, побед: %d.%s", league.id, matches.size(),
                matches.stream().filter(m -> m.won(id)).count(),
                league.remakes > 0 ? " Переигровки (пустая статистика) не учитываются." : "");

        Map<Integer, int[]> picks = new HashMap<>();
        Map<Integer, Integer> ourBans = new HashMap<>(), bansAgainst = new HashMap<>();
        Map<Integer, int[]> enemyPicks = new HashMap<>();
        for (League.Match m : matches) {
            boolean rad = m.isRadiant(id);
            boolean won = m.won(id);
            md.h2(m.date() + " — против " + m.opponentName(id) + ": " + (won ? "победа" : "поражение"));
            md.line("%s, %d мин, [OpenDota](https://www.opendota.com/matches/%d), [Dotabuff](https://ru.dotabuff.com/matches/%d)",
                    rad ? "Radiant" : "Dire", m.durationSec() / 60, m.matchId(), m.matchId());
            Md.Table t = md.table("Игрок", "Герой", "Линия", "GPM", "K/D/A");
            for (League.Player p : m.side(rad)) {
                t.row(nickOrPersona(p), meta.name(p.heroId()), lane(p.laneRole()), p.gpm(),
                        p.kills() + "/" + p.deaths() + "/" + p.assists());
                picks.computeIfAbsent(p.heroId(), k -> new int[2])[0]++;
                picks.get(p.heroId())[1] += won ? 1 : 0;
            }
            t.end();
            md.blank().line("Соперник: %s", m.side(!rad).stream()
                    .map(p -> meta.name(p.heroId()) + " (" + p.personaName() + ")").collect(Collectors.joining(", ")));
            for (League.Player p : m.side(!rad)) {
                enemyPicks.computeIfAbsent(p.heroId(), k -> new int[2])[0]++;
                enemyPicks.get(p.heroId())[1] += won ? 0 : 1;
            }
            md.blank().line("Драфт по порядку (**жирным** — ходы этой команды, b — бан, P — пик):").blank();
            md.line(m.draft().stream().map(d -> {
                String step = (d.pick() ? "P " : "b ") + meta.name(d.heroId());
                return d.radiant() == rad ? "**" + step + "**" : step;
            }).collect(Collectors.joining(" → ")));
            for (League.DraftStep d : m.draft()) {
                if (!d.pick()) {
                    (d.radiant() == rad ? ourBans : bansAgainst).merge(d.heroId(), 1, Integer::sum);
                }
            }
        }

        md.h2("Сводка");
        md.line("**Пики команды** (в скобках — победы): %s", countList(picks, true));
        md.blank().line("**Баны команды:** %s", countList(ourBans));
        md.blank().line("**Банят против команды** (чего боятся соперники): %s", countList(bansAgainst));
        md.blank().line("**Пики соперников** (в скобках — их победы): %s", countList(enemyPicks, true));
        return md.toString();
    }

    private static String lane(int laneRole) {
        return switch (laneRole) {
            case 1 -> "safe";
            case 2 -> "mid";
            case 3 -> "off";
            case 4 -> "лес";
            default -> "—";
        };
    }

    private String countList(Map<Integer, Integer> counts) {
        if (counts.isEmpty()) {
            return "—";
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed().thenComparing(e -> meta.name(e.getKey())))
                .map(e -> meta.name(e.getKey()) + (e.getValue() > 1 ? " ×" + e.getValue() : ""))
                .collect(Collectors.joining(", "));
    }

    private String countList(Map<Integer, int[]> gamesWins, boolean withWins) {
        if (gamesWins.isEmpty()) {
            return "—";
        }
        return gamesWins.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<Integer, int[]> e) -> -e.getValue()[0]).thenComparing(e -> meta.name(e.getKey())))
                .map(e -> meta.name(e.getKey()) + (e.getValue()[0] > 1 ? " ×" + e.getValue()[0] : "")
                        + (withWins ? " (" + e.getValue()[1] + ")" : ""))
                .collect(Collectors.joining(", "));
    }

    private String opponentReadme() {
        Md md = new Md().h1("Соперник: " + teamName());
        md.line("Сгенерировано %s, патч %s. Позиции — по турнирным матчам (см. `config/players.yaml`).", today, meta.patch);
        md.h2("Файлы");
        md.line("| Файл | Что внутри |").line("|---|---|");
        md.line("| [game_plan.md](game_plan.md) | **План на игру** против них (пишется вручную) |");
        md.line("| [team/threats.md](team/threats.md) | Их опасные герои, что они банят, чем их контрить из наших пулов |");
        md.line("| [team/tournament.md](team/tournament.md) | Их турнирные матчи и драфты |");
        md.line("| [team/roster.md](team/roster.md) | Состав, ранги, на каких позициях играют в пабах, сыгранность |");
        for (PlayerStats p : byPosition()) {
            md.line("| [players/%s](players/%s) | Позиция %d, %s: лучшие герои и герои под патч |",
                    playerFile(p), playerFile(p), pos.get(p), p.nick());
        }
        md.blank().line("Как считаются оценки — в [README анализа](../../README.md).");
        return md.toString();
    }

    record Threat(PlayerStats player, int hero, double score, double skill, double metaStrength, int tourneyPicks) {
    }

    /** Угрозы соперника (this) с точки зрения нашей команды (ourSquad). */
    private String threats(Analyze ourSquad) {
        Md md = new Md().h1("Угрозы: " + teamName());
        md.line("Сгенерировано %s, патч %s.", today, meta.patch);

        Map<Long, Map<Integer, Integer>> tourneyPicks = new HashMap<>();
        Map<Integer, Integer> theirBans = new HashMap<>();
        if (league != null && team.teamId() != null) {
            for (League.Match m : league.of(team.teamId())) {
                boolean rad = m.isRadiant(team.teamId());
                m.side(rad).forEach(p -> tourneyPicks.computeIfAbsent(p.accountId(), k -> new HashMap<>())
                        .merge(p.heroId(), 1, Integer::sum));
                m.draft().stream().filter(d -> !d.pick() && d.radiant() == rad)
                        .forEach(d -> theirBans.merge(d.heroId(), 1, Integer::sum));
            }
        }

        md.h2("Пулы по позициям (топ-6 по пригодности в патче)");
        Md.Table pool = md.table("Поз.", "Игрок", "Ранг", "Кандидаты (пригодность; ▲ — сильный в патче)", "В турнире");
        for (PlayerStats p : byPosition()) {
            int[] r = bracketRange(p);
            Map<Integer, Integer> tp = tourneyPicks.getOrDefault(p.config.accountId(), Map.of());
            pool.row(pos.get(p), "[" + p.nick() + "](../players/" + playerFile(p) + ")", Meta.bracketName(p.rankTier),
                    patchFit(p, pos.get(p), 6).stream()
                            .map(f -> meta.name(f.hero()) + " " + Md.num(f.fit()) + (meta.winrate(f.hero(), r[0], r[1]) >= Meta.STRONG_WR ? " ▲" : ""))
                            .collect(Collectors.joining(", ")),
                    tp.isEmpty() ? "—" : countList(tp));
        }
        pool.end();

        // Угроза = умение игрока на герое + сила героя в мете; бонус за пик в турнире
        List<Threat> list = new ArrayList<>();
        for (PlayerStats p : players) {
            int[] r = bracketRange(p);
            Map<Integer, Integer> tp = tourneyPicks.getOrDefault(p.config.accountId(), Map.of());
            for (PlayerStats.HeroLine h : p.heroes.values()) {
                if (h.games < 5 || h.wGames < 1.0) {
                    continue;
                }
                int mp = Math.max(1, meta.mainPos(h.heroId));
                double ms = metaStrength(h.heroId, mp, r[0], r[1]);
                int picksInTourney = tp.getOrDefault(h.heroId, 0);
                double score = 0.6 * h.skill / 100 + 0.4 * ms + (picksInTourney > 0 ? 0.1 : 0);
                list.add(new Threat(p, h.heroId, score, h.skill, ms, picksInTourney));
            }
        }
        list.sort(Comparator.comparingDouble(Threat::score).reversed());
        // герой может быть у нескольких игроков — оставляем самого опасного
        Set<Integer> seenHero = new HashSet<>();
        List<Threat> top = list.stream().filter(t -> seenHero.add(t.hero())).limit(15).toList();

        md.h2("Самые опасные герои (кандидаты в баны)");
        md.line("Угроза = 60% умение игрока на герое + 40% сила героя в мете на его ранге; +0.1, если герой уже был в турнире.").blank();
        Md.Table th = md.table("#", "Герой", "Игрок", "Угроза", "Умение", "Сила в мете", "Игр всего / год", "WR за год", "В турнире", "Позиции героя");
        int i = 1;
        for (Threat t : top) {
            PlayerStats.HeroLine h = t.player().heroes.get(t.hero());
            th.row(i++, meta.name(t.hero()), t.player().nick(), Md.dec(t.score()), Md.num(t.skill()), Md.dec(t.metaStrength()),
                    h.games + " / " + h.yearGames, h.yearGames == 0 ? "—" : Md.pct((double) h.yearWins / h.yearGames),
                    t.tourneyPicks() == 0 ? "" : "×" + t.tourneyPicks(), meta.positions(t.hero()));
        }
        th.end();

        md.h2("Что они банят в турнире");
        md.line(countList(theirBans));
        Set<Integer> ourPool = ourSquad.players.stream()
                .flatMap(p -> ourSquad.patchFit(p, ourSquad.pos.get(p), 10).stream().filter(f -> f.skill() > 0))
                .map(Fit::hero).collect(Collectors.toSet());
        List<String> hitsUs = theirBans.keySet().stream().filter(ourPool::contains).map(meta::name).sorted().toList();
        md.blank().line("Из наших пулов они уже банили: %s", hitsUs.isEmpty() ? "ничего" : String.join(", ", hitsUs));

        md.h2("Чем контрить их главных героев (из наших пулов)");
        md.line("Для топ-8 угроз — наши герои (топ-10 пулов, с опытом), у которых преимущество против них ≥ +2%% (минимум %d игр).", MIN_PAIR_GAMES).blank();
        Md.Table ct = md.table("Их герой", "Игрок", "Наши ответы (игрок, преимущество)");
        for (Threat t : top.stream().limit(8).toList()) {
            // герой → кто из наших его играет; лучшие 6 по преимуществу
            Map<Integer, List<String>> who = new LinkedHashMap<>();
            for (PlayerStats our : ourSquad.byPosition()) {
                for (Fit f : ourSquad.patchFit(our, ourSquad.pos.get(our), 10)) {
                    if (f.skill() > 0 && meta.counterAdvantage(f.hero(), t.hero(), MIN_PAIR_GAMES) >= 0.02) {
                        who.computeIfAbsent(f.hero(), k -> new ArrayList<>()).add(our.nick());
                    }
                }
            }
            String answers = who.keySet().stream()
                    .sorted(Comparator.comparingDouble((Integer h) -> meta.counterAdvantage(h, t.hero(), MIN_PAIR_GAMES)).reversed())
                    .limit(6)
                    .map(h -> meta.name(h) + " " + Md.signedPct(meta.counterAdvantage(h, t.hero(), MIN_PAIR_GAMES))
                            + " (" + String.join("/", who.get(h)) + ")")
                    .collect(Collectors.joining(", "));
            ct.row(meta.name(t.hero()), t.player().nick(), answers.isEmpty() ? "—" : answers);
        }
        ct.end();

        // против кого они проигрывают: их матчи за 2 года, общий матч — один раз
        Map<Integer, int[]> vs = new HashMap<>();
        Set<String> seen = new HashSet<>();
        for (PlayerStats p : players) {
            for (PlayerStats.Enemy e : p.enemies) {
                if (seen.add(e.matchId() + ":" + e.heroId())) {
                    int[] acc = vs.computeIfAbsent(e.heroId(), k -> new int[2]);
                    acc[0]++;
                    acc[1] += e.win() ? 1 : 0;
                }
            }
        }
        int g = vs.values().stream().mapToInt(a -> a[0]).sum(), w = vs.values().stream().mapToInt(a -> a[1]).sum();
        double avg = g == 0 ? 0.5 : (double) w / g;
        md.h2("Против кого они проигрывают");
        md.line("Их матчи за 2 года (без турбо), сглажено к их среднему (%s) весом 30 игр; минимум 40 игр. ✔ — герой есть в наших пулах.", Md.pct(avg)).blank();
        Md.Table nem = md.table("#", "Герой", "Их WR против", "Игр", "В наших пулах");
        i = 1;
        for (int h : vs.keySet().stream().filter(h -> vs.get(h)[0] >= 40)
                .sorted(Comparator.comparingDouble(h -> Scoring.shrink(vs.get(h)[1], vs.get(h)[0], avg, 30))).limit(12).toList()) {
            nem.row(i++, meta.name(h), Md.pct(Scoring.shrink(vs.get(h)[1], vs.get(h)[0], avg, 30)), vs.get(h)[0],
                    ourPool.contains(h) ? "✔" : "");
        }
        nem.end();
        return md.toString();
    }

    /** Результаты и драфты всех матчей между командами группы (наша + league.group). */
    static String groupReport(Meta meta, League league, PlayerConfig config) {
        Map<Long, String> names = new LinkedHashMap<>();
        if (config.ourTeam() != null && config.ourTeam().teamId() != null) {
            names.put(config.ourTeam().teamId(), config.ourTeam().name());
        }
        config.league().groupOrEmpty().forEach(t -> names.put(t.teamId(), t.name()));
        List<League.Match> matches = league.matches.stream()
                .filter(m -> names.containsKey(m.radiantTeamId()) && names.containsKey(m.direTeamId())).toList();

        Md md = new Md().h1("Группа: " + config.league().name());
        md.line("Матчи между командами группы из лиги OpenDota %d. Переигровок отброшено (по всей лиге): %d.", league.id, league.remakes);
        md.h2("Карты");
        Md.Table st = md.table("Команда", "Карт", "Побед", "Поражений");
        for (var e : names.entrySet()) {
            long games = matches.stream().filter(m -> m.involves(e.getKey())).count();
            long wins = matches.stream().filter(m -> m.involves(e.getKey()) && m.won(e.getKey())).count();
            st.row(e.getValue(), games, wins, games - wins);
        }
        st.end();
        md.h2("Матчи");
        Md.Table mt = md.table("Дата", "Radiant", "Dire", "Победитель", "Мин", "Пики Radiant", "Пики Dire", "Матч");
        for (League.Match m : matches) {
            mt.row(m.date(), m.radiantName(), m.direName(), m.radiantWin() ? m.radiantName() : m.direName(),
                    m.durationSec() / 60,
                    m.side(true).stream().map(p -> meta.name(p.heroId())).collect(Collectors.joining(", ")),
                    m.side(false).stream().map(p -> meta.name(p.heroId())).collect(Collectors.joining(", ")),
                    "[" + m.matchId() + "](https://www.opendota.com/matches/" + m.matchId() + ")");
        }
        mt.end();
        return md.toString();
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
