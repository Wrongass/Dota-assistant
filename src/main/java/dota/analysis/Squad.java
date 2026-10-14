package dota.analysis;

import dota.fetch.PlayerConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Команда (наша или соперник): игроки, их позиции и все расчёты по ней. Строит {@link Views}. */
public final class Squad {

    static final int TOP = 15;
    /** Минимум игр пары в public-выборке, чтобы показывать синергию/контрпик. */
    static final long MIN_PAIR_GAMES = 200;

    final Meta meta;
    final List<PlayerStats> players;
    final Map<PlayerStats, Integer> pos = new LinkedHashMap<>();
    final Map<PlayerStats, Boolean> posInferred = new HashMap<>();
    final PlayerConfig.TeamInfo team;
    final boolean ours;
    final League league;
    /** Диапазон рангов команды для меты: медиана ±1 корзина. */
    final int teamFrom, teamTo;
    final String today;

    Squad(Meta meta, List<PlayerStats> players, PlayerConfig.TeamInfo team, boolean ours, League league, String today) {
        this.meta = meta;
        this.players = players;
        this.team = team;
        this.ours = ours;
        this.league = league;
        this.today = today;
        assignPositions();
        int[] brackets = players.stream().mapToInt(PlayerStats::bracket).sorted().toArray();
        int median = brackets.length == 0 ? 5 : brackets[brackets.length / 2];
        this.teamFrom = Math.max(1, median - 1);
        this.teamTo = Math.min(8, median + 1);
    }

    // ---------------------------------------------------------------- позиции и имена

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

    static List<int[]> permutations(int[] items, int k) {
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

    List<PlayerStats> byPosition() {
        return players.stream().sorted(Comparator.comparingInt(pos::get)).toList();
    }

    int position(PlayerStats p) {
        return pos.get(p);
    }

    String playerFile(PlayerStats p) {
        return "pos" + pos.get(p) + "_" + p.nick() + ".md";
    }

    public String slug() {
        return team.name().replaceAll("[^\\p{L}\\p{N}]+", "_");
    }

    public String teamName() {
        if (team == null || team.name() == null) {
            return "наша команда";
        }
        return team.name() + (team.organization() == null ? "" : " (" + team.organization() + ")");
    }

    public boolean ours() {
        return ours;
    }

    public PlayerConfig.TeamInfo team() {
        return team;
    }

    public List<Views.PlayerRef> playerRefs() {
        return byPosition().stream()
                .map(p -> new Views.PlayerRef(pos.get(p), p.nick(), playerFile(p), p.config.accountId()))
                .toList();
    }

    PlayerStats player(long accountId) {
        return players.stream().filter(p -> p.config.accountId() == accountId).findFirst().orElse(null);
    }

    boolean hasTournament() {
        return league != null && team != null && team.teamId() != null;
    }

    /** Есть ли лига и team_id — тогда доступен {@link #tournament()}. */
    public boolean hasTournamentData() {
        return hasTournament();
    }

    /** Карточка игрока по account_id (игрок должен быть в этой команде). */
    public Views.PlayerCard playerCard(long accountId) {
        PlayerStats p = player(accountId);
        if (p == null) {
            throw new IllegalArgumentException("нет игрока " + accountId + " в " + teamName());
        }
        return playerCard(p);
    }

    // ---------------------------------------------------------------- оценки

    int[] bracketRange(PlayerStats p) {
        int b = p.bracket();
        return new int[]{Math.max(1, b - 1), Math.min(8, b + 1)};
    }

    double metaStrength(int hero, int position, int from, int to) {
        return Scoring.metaStrength(meta.winrate(hero, from, to), meta.posWinrate(hero, position), meta.proContest(hero));
    }

    static String label(double wr) {
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
    List<Integer> standouts(int position, int from, int to, boolean strong, int limit) {
        Comparator<Integer> byWr = Comparator.comparingDouble(h -> meta.winrate(h, from, to));
        return meta.heroes.keySet().stream()
                .filter(h -> meta.posTotal(h) >= 20 && meta.posShare(h, position) >= 0.25)
                .filter(h -> meta.pickRate(h, from, to) >= 0.01)
                .filter(h -> strong ? meta.winrate(h, from, to) >= Meta.STRONG_WR : meta.winrate(h, from, to) <= Meta.WEAK_WR)
                .sorted(strong ? byWr.reversed() : byWr)
                .limit(limit)
                .toList();
    }

    String rangeName(int from, int to) {
        return from == to ? Meta.BRACKET_RU[from] : Meta.BRACKET_RU[from] + "–" + Meta.BRACKET_RU[to];
    }

    private List<Views.HeroValue> heroValues(List<Integer> heroes, int from, int to) {
        return heroes.stream().map(h -> new Views.HeroValue(h, meta.name(h), meta.winrate(h, from, to))).toList();
    }

    /** Пул игрока: топ по пригодности на его позиции (только для отображения). */
    List<Views.PoolEntry> pool(PlayerStats p, int limit) {
        int[] r = bracketRange(p);
        return patchFit(p, pos.get(p), limit).stream()
                .map(f -> new Views.PoolEntry(f.hero(), meta.name(f.hero()), f.fit(),
                        meta.winrate(f.hero(), r[0], r[1]) >= Meta.STRONG_WR))
                .toList();
    }

    /** Герои из топ-10 пулов, на которых игрок реально играл (умение > 0). */
    Set<Integer> experiencedPool() {
        return players.stream()
                .flatMap(p -> patchFit(p, pos.get(p), 10).stream().filter(f -> f.skill() > 0))
                .map(Fit::hero).collect(Collectors.toSet());
    }

    /** Вражеские герои по всем матчам игроков за 2 года; общий матч считается один раз. [игры, победы]. */
    Map<Integer, int[]> vsEnemies() {
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
        return vs;
    }

    static double average(Map<Integer, int[]> vs) {
        int g = vs.values().stream().mapToInt(a -> a[0]).sum();
        int w = vs.values().stream().mapToInt(a -> a[1]).sum();
        return g == 0 ? 0.5 : (double) w / g;
    }

    // ---------------------------------------------------------------- view-модели

    public Views.Roster roster() {
        List<Views.RosterRow> rows = new ArrayList<>();
        List<Views.PosShareRow> shares = new ArrayList<>();
        for (PlayerStats p : byPosition()) {
            rows.add(new Views.RosterRow(pos.get(p), posInferred.get(p), p.nick(), playerFile(p), p.config.accountId(),
                    p.personaName, p.rankTier, p.computedMmr, p.totalGames, p.yearGames, p.patchGames, p.patchWins,
                    p.cmGames, p.cmWins, p.config.note()));
            double[] share = new double[5];
            for (int i = 0; i < 5; i++) {
                share[i] = p.yearShareOnPos(meta, i + 1);
            }
            shares.add(new Views.PosShareRow(p.nick(), pos.get(p), share,
                    new int[]{p.yearLaneRole[1], p.yearLaneRole[2], p.yearLaneRole[3], p.yearLaneRole[4]}));
        }
        List<PlayerStats> ordered = byPosition();
        int n = ordered.size();
        int[][] games = new int[n][n], wins = new int[n][n];
        for (int a = 0; a < n; a++) {
            for (int b = 0; b < n; b++) {
                long id = ordered.get(b).config.accountId();
                games[a][b] = a == b ? -1 : ordered.get(a).withTeammate.getOrDefault(id, 0);
                wins[a][b] = a == b ? -1 : ordered.get(a).winsWithTeammate.getOrDefault(id, 0);
            }
        }
        return new Views.Roster(teamName(), today, meta.patch, rows, posInferred.containsValue(true), shares,
                ordered.stream().map(PlayerStats::nick).toList(), games, wins);
    }

    Views.PlayerCard playerCard(PlayerStats p) {
        int position = pos.get(p);
        int[] r = bracketRange(p);
        List<Views.BestHero> best = new ArrayList<>();
        for (PlayerStats.HeroLine h : p.bestHeroes(TOP)) {
            best.add(new Views.BestHero(h.heroId, meta.name(h.heroId), h.skill, h.games, h.yearGames, h.patchGames,
                    h.winrate(), h.yearGames == 0 ? Double.NaN : (double) h.yearWins / h.yearGames, h.percentRank,
                    h.kda(), meta.positions(h.heroId), meta.winrate(h.heroId, r[0], r[1])));
        }
        List<Views.FitRow> fits = new ArrayList<>();
        for (Fit f : patchFit(p, position, TOP)) {
            PlayerStats.HeroLine h = p.heroes.get(f.hero());
            double wr = meta.winrate(f.hero(), r[0], r[1]);
            String mark = label(wr);
            if (position == 3 && meta.posShare(f.hero(), 1) >= 0.2) {
                mark = (mark + " может в керри").trim();
            }
            if (h == null || h.games < 3) {
                mark = (mark + " нет опыта").trim();
            }
            fits.add(new Views.FitRow(f.hero(), meta.name(f.hero()), f.fit(), f.meta(), wr,
                    meta.posWinrate(f.hero(), position), meta.proContest(f.hero()), f.skill(),
                    h == null ? 0 : h.games, mark));
        }
        List<Views.HeroValue> weakInPool = p.bestHeroes(TOP).stream()
                .filter(h -> meta.winrate(h.heroId, r[0], r[1]) <= Meta.WEAK_WR)
                .map(h -> new Views.HeroValue(h.heroId, meta.name(h.heroId), meta.winrate(h.heroId, r[0], r[1])))
                .toList();
        return new Views.PlayerCard(position, p.nick(), p.personaName, p.config.accountId(), p.rankTier,
                p.computedMmr, p.totalGames, p.yearGames, meta.patch, p.patchGames, p.patchWins, p.cmGames,
                p.config.note(), rangeName(r[0], r[1]), best, fits,
                heroValues(standouts(position, r[0], r[1], true, 10), r[0], r[1]),
                heroValues(standouts(position, r[0], r[1], false, 8), r[0], r[1]), weakInPool);
    }

    /** Мета на рангах этой команды (медиана ±1). */
    public Views.MetaView metaView() {
        List<Views.PositionMeta> positions = new ArrayList<>();
        for (int position = 1; position <= 5; position++) {
            final int ps = position;
            List<Views.MetaRow> top = meta.heroes.keySet().stream()
                    .filter(h -> meta.posTotal(h) >= 20 && meta.posShare(h, ps) >= 0.25)
                    .filter(h -> meta.pickRate(h, teamFrom, teamTo) >= 0.01)
                    .sorted(Comparator.comparingDouble((Integer h) -> metaStrength(h, ps, teamFrom, teamTo)).reversed())
                    .limit(12)
                    .map(h -> new Views.MetaRow(h, meta.name(h), metaStrength(h, ps, teamFrom, teamTo),
                            meta.winrate(h, teamFrom, teamTo), meta.pickRate(h, teamFrom, teamTo),
                            meta.posWinrate(h, ps), meta.proContest(h)))
                    .toList();
            positions.add(new Views.PositionMeta(position, top,
                    heroValues(standouts(position, teamFrom, teamTo, false, 8), teamFrom, teamTo)));
        }
        List<Views.ProRow> pro = meta.heroes.keySet().stream()
                .sorted(Comparator.comparingDouble(meta::proContest).reversed()).limit(20)
                .map(h -> {
                    Meta.HeroStats s = meta.stats.get(h);
                    return new Views.ProRow(h, meta.name(h), meta.proContest(h), s.proPick(), s.proBan(),
                            s.proPick() == 0 ? Double.NaN : (double) s.proWin() / s.proPick(), meta.positions(h));
                })
                .toList();
        List<Views.PairRow> synergies = meta.synergy.entrySet().stream()
                .filter(e -> e.getValue().games() >= 400)
                .sorted(Comparator.comparingDouble((Map.Entry<Meta.Pair, Meta.PairStats> e) ->
                        meta.synergyLift(e.getKey().a(), e.getKey().b(), 400)).reversed())
                .limit(30)
                .map(e -> pairRow(e.getKey().a(), e.getKey().b(), meta.synergyLift(e.getKey().a(), e.getKey().b(), 400), e.getValue()))
                .toList();
        List<Views.PairRow> counters = meta.counters.entrySet().stream()
                .filter(e -> e.getValue().games() >= 400)
                .sorted(Comparator.comparingDouble((Map.Entry<Meta.Pair, Meta.PairStats> e) ->
                        meta.counterAdvantage(e.getKey().a(), e.getKey().b(), 400)).reversed())
                .limit(30)
                .map(e -> pairRow(e.getKey().a(), e.getKey().b(), meta.counterAdvantage(e.getKey().a(), e.getKey().b(), 400), e.getValue()))
                .toList();
        return new Views.MetaView(meta.patch, today, rangeName(teamFrom, teamTo), meta.proMatches, positions, pro,
                synergies, counters);
    }

    private Views.PairRow pairRow(int a, int b, double value, Meta.PairStats s) {
        return new Views.PairRow(a, meta.name(a), b, meta.name(b), value, s.winrate(), s.games());
    }

    public Views.DraftData draftData() {
        Map<PlayerStats, List<Fit>> pools = new LinkedHashMap<>();
        for (PlayerStats p : byPosition()) {
            pools.put(p, patchFit(p, pos.get(p), 10));
        }
        List<Views.Pool> poolViews = new ArrayList<>();
        for (PlayerStats p : pools.keySet()) {
            poolViews.add(new Views.Pool(pos.get(p), p.nick(), pool(p, 6)));
        }

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
        List<Views.ComboRow> comboRows = combos.stream().limit(25)
                .map(c -> new Views.ComboRow(pairRow(c.ha(), c.hb(), c.lift(), c.s()), c.a().nick() + " + " + c.b().nick()))
                .toList();

        // Неудобные герои: по всем нашим игрокам за 2 года, общий матч считается один раз
        Map<Integer, int[]> vs = vsEnemies();
        double avg = average(vs);
        Map<Integer, Double> vsWr = new HashMap<>();
        vs.forEach((h, a) -> vsWr.put(h, Scoring.shrink(a[1], a[0], avg, 30)));
        List<Views.VsRow> nemesis = vs.keySet().stream().filter(h -> vs.get(h)[0] >= 40)
                .sorted(Comparator.comparingDouble(vsWr::get)).limit(15)
                .map(h -> new Views.VsRow(h, meta.name(h), vsWr.get(h), vs.get(h)[0], meta.winrate(h, teamFrom, teamTo)))
                .toList();
        List<Views.HeroValue> bestAgainst = vs.keySet().stream().filter(h -> vs.get(h)[0] >= 40)
                .sorted(Comparator.comparingDouble((Integer h) -> vsWr.get(h)).reversed()).limit(10)
                .map(h -> new Views.HeroValue(h, meta.name(h), vsWr.get(h)))
                .toList();

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
        List<Views.BanRow> bans = banScore.keySet().stream()
                .sorted(Comparator.comparingDouble(banScore::get).reversed()).limit(15)
                .map(h -> new Views.BanRow(h, meta.name(h), banScore.get(h),
                        vs.containsKey(h) ? vsWr.get(h) : Double.NaN, vs.containsKey(h) ? vs.get(h)[0] : 0,
                        meta.winrate(h, teamFrom, teamTo), meta.proContest(h), meta.positions(h)))
                .toList();

        List<Views.CounterThreat> threats = new ArrayList<>();
        for (var e : pools.entrySet()) {
            for (Fit f : e.getValue().stream().limit(3).toList()) {
                List<Views.HeroValue> list = meta.heroes.keySet().stream()
                        .filter(h -> !Double.isNaN(meta.counterAdvantage(h, f.hero(), MIN_PAIR_GAMES)))
                        .sorted(Comparator.comparingDouble((Integer h) -> meta.counterAdvantage(h, f.hero(), MIN_PAIR_GAMES)).reversed())
                        .limit(4)
                        .map(h -> new Views.HeroValue(h, meta.name(h), meta.counterAdvantage(h, f.hero(), MIN_PAIR_GAMES)))
                        .toList();
                threats.add(new Views.CounterThreat(e.getKey().nick(), f.hero(), meta.name(f.hero()), list));
            }
        }
        return new Views.DraftData(teamName(), today, meta.patch, rangeName(teamFrom, teamTo), poolViews, comboRows,
                avg, nemesis, bestAgainst, bans, threats);
    }

    private String nickOrPersona(League.Player p) {
        return players.stream().filter(s -> s.config.accountId() == p.accountId()).findFirst()
                .map(PlayerStats::nick).orElse(p.personaName().isEmpty() ? String.valueOf(p.accountId()) : p.personaName());
    }

    /** Турнирные матчи команды (team.teamId): драфты, герои игроков, сводка по пикам и банам. */
    public Views.Tournament tournament() {
        long id = team.teamId();
        List<League.Match> matches = league.of(id);
        Map<Integer, int[]> picks = new HashMap<>(), enemyPicks = new HashMap<>();
        Map<Integer, Integer> ourBans = new HashMap<>(), bansAgainst = new HashMap<>();
        List<Views.MatchView> views = new ArrayList<>();
        for (League.Match m : matches) {
            boolean rad = m.isRadiant(id);
            boolean won = m.won(id);
            List<Views.MatchPlayer> ourSide = new ArrayList<>();
            for (League.Player p : m.side(rad)) {
                ourSide.add(matchPlayer(nickOrPersona(p), p));
                picks.computeIfAbsent(p.heroId(), k -> new int[2])[0]++;
                picks.get(p.heroId())[1] += won ? 1 : 0;
            }
            List<Views.MatchPlayer> enemies = new ArrayList<>();
            for (League.Player p : m.side(!rad)) {
                enemies.add(matchPlayer(p.personaName(), p));
                enemyPicks.computeIfAbsent(p.heroId(), k -> new int[2])[0]++;
                enemyPicks.get(p.heroId())[1] += won ? 0 : 1;
            }
            List<Views.Step> draft = new ArrayList<>();
            for (League.DraftStep d : m.draft()) {
                draft.add(new Views.Step(d.pick(), d.radiant() == rad, d.heroId(), meta.name(d.heroId())));
                if (!d.pick()) {
                    (d.radiant() == rad ? ourBans : bansAgainst).merge(d.heroId(), 1, Integer::sum);
                }
            }
            views.add(new Views.MatchView(m.matchId(), m.date(), m.opponentName(id), won, rad, m.durationSec() / 60,
                    ourSide, enemies, draft));
        }
        return new Views.Tournament(teamName(), league.id, matches.stream().filter(m -> m.won(id)).count(),
                league.remakes > 0, views, gameCounts(picks), timesCounts(ourBans), timesCounts(bansAgainst), gameCounts(enemyPicks));
    }

    private Views.MatchPlayer matchPlayer(String nick, League.Player p) {
        return new Views.MatchPlayer(nick, p.heroId(), meta.name(p.heroId()), p.laneRole(), p.gpm(),
                p.kills(), p.deaths(), p.assists());
    }

    /** Счётчик «сколько раз»: по убыванию, при равенстве — по имени. */
    List<Views.Count> timesCounts(Map<Integer, Integer> counts) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed().thenComparing(e -> meta.name(e.getKey())))
                .map(e -> new Views.Count(e.getKey(), meta.name(e.getKey()), e.getValue(), 0))
                .toList();
    }

    /** Счётчик «игры и победы»: по убыванию игр, при равенстве — по имени. */
    List<Views.Count> gameCounts(Map<Integer, int[]> gamesWins) {
        return gamesWins.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<Integer, int[]> e) -> -e.getValue()[0]).thenComparing(e -> meta.name(e.getKey())))
                .map(e -> new Views.Count(e.getKey(), meta.name(e.getKey()), e.getValue()[0], e.getValue()[1]))
                .toList();
    }

    /** Пики в турнире: account_id → герой → сколько раз; и баны команды. */
    Map<Long, Map<Integer, Integer>> tournamentPicks() {
        Map<Long, Map<Integer, Integer>> picks = new HashMap<>();
        if (hasTournament()) {
            for (League.Match m : league.of(team.teamId())) {
                boolean rad = m.isRadiant(team.teamId());
                m.side(rad).forEach(p -> picks.computeIfAbsent(p.accountId(), k -> new HashMap<>())
                        .merge(p.heroId(), 1, Integer::sum));
            }
        }
        return picks;
    }

    Map<Integer, Integer> tournamentBans() {
        Map<Integer, Integer> bans = new HashMap<>();
        if (hasTournament()) {
            for (League.Match m : league.of(team.teamId())) {
                boolean rad = m.isRadiant(team.teamId());
                m.draft().stream().filter(d -> !d.pick() && d.radiant() == rad)
                        .forEach(d -> bans.merge(d.heroId(), 1, Integer::sum));
            }
        }
        return bans;
    }

    record Threat(PlayerStats player, int hero, double score, double skill, double metaStrength, int tourneyPicks) {
    }

    /**
     * Угроза = 60% умение игрока на герое + 40% сила героя в мете на его ранге; +0.1, если герой был в турнире.
     * Герой может быть у нескольких игроков — остаётся самый опасный.
     */
    List<Threat> threatList(Map<Long, Map<Integer, Integer>> tourneyPicks) {
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
        Set<Integer> seenHero = new HashSet<>();
        return list.stream().filter(t -> seenHero.add(t.hero())).toList();
    }

    /** Угрозы этой команды (соперник) с точки зрения нашей команды. */
    public Views.Threats threats(Squad ourSquad) {
        Map<Long, Map<Integer, Integer>> tourneyPicks = tournamentPicks();
        Map<Integer, Integer> theirBans = tournamentBans();

        List<Views.OppPool> pools = new ArrayList<>();
        for (PlayerStats p : byPosition()) {
            pools.add(new Views.OppPool(pos.get(p), p.nick(), playerFile(p), p.rankTier, pool(p, 6),
                    timesCounts(tourneyPicks.getOrDefault(p.config.accountId(), Map.of()))));
        }

        List<Threat> top = threatList(tourneyPicks).stream().limit(15).toList();
        List<Views.ThreatRow> threatRows = top.stream().map(t -> {
            PlayerStats.HeroLine h = t.player().heroes.get(t.hero());
            return new Views.ThreatRow(t.hero(), meta.name(t.hero()), t.player().nick(), t.score(), t.skill(),
                    t.metaStrength(), h.games, h.yearGames, h.yearWins, t.tourneyPicks(), meta.positions(t.hero()));
        }).toList();

        Set<Integer> ourPool = ourSquad.experiencedPool();
        List<String> hitsUs = theirBans.keySet().stream().filter(ourPool::contains).map(meta::name).sorted().toList();

        List<Views.CounterRow> counterRows = new ArrayList<>();
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
            List<Views.Answer> answers = who.keySet().stream()
                    .sorted(Comparator.comparingDouble((Integer h) -> meta.counterAdvantage(h, t.hero(), MIN_PAIR_GAMES)).reversed())
                    .limit(6)
                    .map(h -> new Views.Answer(h, meta.name(h), meta.counterAdvantage(h, t.hero(), MIN_PAIR_GAMES), who.get(h)))
                    .toList();
            counterRows.add(new Views.CounterRow(t.hero(), meta.name(t.hero()), t.player().nick(), answers));
        }

        Map<Integer, int[]> vs = vsEnemies();
        double avg = average(vs);
        List<Views.LossRow> losses = vs.keySet().stream().filter(h -> vs.get(h)[0] >= 40)
                .sorted(Comparator.comparingDouble(h -> Scoring.shrink(vs.get(h)[1], vs.get(h)[0], avg, 30))).limit(12)
                .map(h -> new Views.LossRow(h, meta.name(h), Scoring.shrink(vs.get(h)[1], vs.get(h)[0], avg, 30),
                        vs.get(h)[0], ourPool.contains(h)))
                .toList();
        return new Views.Threats(teamName(), today, meta.patch, pools, threatRows, timesCounts(theirBans), hitsUs,
                counterRows, avg, losses);
    }

    /** Результаты и драфты всех матчей между командами группы (наша + league.group). */
    static Views.Group group(Meta meta, League league, PlayerConfig config) {
        Map<Long, String> names = new LinkedHashMap<>();
        if (config.ourTeam() != null && config.ourTeam().teamId() != null) {
            names.put(config.ourTeam().teamId(), config.ourTeam().name());
        }
        config.league().groupOrEmpty().forEach(t -> names.put(t.teamId(), t.name()));
        List<League.Match> matches = league.matches.stream()
                .filter(m -> names.containsKey(m.radiantTeamId()) && names.containsKey(m.direTeamId())).toList();
        List<Views.Standing> standings = names.entrySet().stream().map(e -> new Views.Standing(e.getValue(),
                matches.stream().filter(m -> m.involves(e.getKey())).count(),
                matches.stream().filter(m -> m.involves(e.getKey()) && m.won(e.getKey())).count())).toList();
        List<Views.GroupMatch> list = matches.stream().map(m -> new Views.GroupMatch(m.matchId(), m.date(),
                m.radiantName(), m.direName(), m.radiantWin() ? m.radiantName() : m.direName(), m.durationSec() / 60,
                m.side(true).stream().map(p -> meta.name(p.heroId())).toList(),
                m.side(false).stream().map(p -> meta.name(p.heroId())).toList())).toList();
        return new Views.Group(config.league().name(), league.id, league.remakes, standings, list);
    }
}
