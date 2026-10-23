package dota.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Live-драфт Captains Mode: по уже сделанным ходам — кто у соперника на каком герое, их вероятные следующие пики,
 * кого нам банить и кого брать. Чистая статистика по загруженным данным, без сети: пересчёт — миллисекунды.
 */
public final class DraftEngine {

    /**
     * Порядок CM в нашей лиге (39 из 41 матча, 7 банов и 5 пиков у команды): 1 — команда с первым ходом.
     * b1 b1 b2 b2 b1 b2 b2 · P1 P2 · b1 b1 b2 · P2 P1 P1 P2 P2 P1 · b1 b2 b1 b2 · P1 P2
     */
    public static final String ORDER = "b1 b1 b2 b2 b1 b2 b2 P1 P2 b1 b1 b2 P2 P1 P1 P2 P2 P1 b1 b2 b1 b2 P1 P2";

    public record Slot(boolean pick, boolean firstTeam) {
    }

    public static final List<Slot> SLOTS = parseOrder(ORDER);

    private static List<Slot> parseOrder(String order) {
        List<Slot> slots = new ArrayList<>();
        for (String s : order.split(" ")) {
            slots.add(new Slot(s.charAt(0) == 'P', s.charAt(1) == '1'));
        }
        return List.copyOf(slots);
    }

    /**
     * Настройки модели. Подобраны бэктестом ({@code ./gradlew draftBacktest}), см. {@link DraftBacktest}.
     *
     * @param sharpness степень, в которую возводятся оценки героев игрока перед нормировкой: больше 1 — распределение
     *                  острее, сигнатурные герои получают заметную долю вместо «4% у лучшего»
     * @param shareWeight вес доли героя в играх игрока за год и патч («чем играет в последнее время»)
     * @param shareCap    с какой доли игр вес перестаёт расти
     * @param recentWeight вес доли героя в играх за последние 60 дней (без Турбо) — «что тренирует сейчас»
     * @param teamWeight   вес доли героя в играх за 60 дней в пати с игроками своей команды
     */
    public record Params(double sharpness, double shareWeight, double shareCap, double recentWeight, double teamWeight) {
        public static final Params DEFAULT = new Params(1.25, 32, 0.25, 8, 4);
    }

    /**
     * heroes[i] — герой хода i (0 — ход пропущен). assign — ручное назначение «герой → ник игрока»
     * (капитан знает, кто на каком герое, лучше статистики), для обеих команд.
     */
    public record State(String opponentSlug, boolean weFirst, List<Integer> heroes, Map<Integer, String> assign) {
        public State(String opponentSlug, boolean weFirst, List<Integer> heroes) {
            this(opponentSlug, weFirst, heroes, Map.of());
        }
    }

    public record Reasoned(int heroId, String name, double score, String player, List<String> reasons) {
    }

    /**
     * Кто на герое. player — игрок в самой вероятной расстановке всей команды (один игрок — один герой),
     * probability — вероятность этой пары; alternative — второй по вероятности игрок. fixed — назначено вручную.
     */
    public record Assignment(int heroId, String name, String player, double probability, String alternative,
                             double alternativeProbability, boolean fixed) {
    }

    /** Игрок команды: ник и позиция — для выбора «кто на герое» вручную. */
    public record RosterLine(String player, int position) {
    }

    /** Прогноз по одному игроку соперника: насколько он ещё свободен и какие герои у него вероятнее. */
    public record PlayerOutlook(String player, int position, double free, List<HeroOdds> heroes) {
    }

    public record HeroOdds(int heroId, String name, double probability) {
    }

    /** Линия после драфта: наши и их герои, среднее преимущество наших над их по встречным парам. */
    public record Lane(String name, List<String> ours, List<String> theirs, double advantage, String note) {
    }

    public record Advice(int step, boolean finished, boolean oursNext, boolean pickNext, List<Integer> ourPicks,
                         List<Integer> theirPicks, List<Integer> ourBans, List<Integer> theirBans,
                         List<Assignment> theirAssignment, List<Assignment> ourAssignment,
                         List<Remaining> theirRemaining, List<Remaining> ourRemaining,
                         List<Reasoned> theirNextPicks, List<Reasoned> banSuggestions,
                         List<Reasoned> pickSuggestions, List<Reasoned> theirLikelyBans,
                         List<PlayerOutlook> theirPlayers, List<RosterLine> ourRoster, List<RosterLine> theirRoster,
                         int theirBansLeft, int theirPicksLeft, int ourBansLeft, int ourPicksLeft,
                         List<Lane> lanes, List<Reasoned> theirThreats, long micros) {
    }

    private final AnalysisContext ctx;
    private final Meta meta;
    private final Params params;

    public DraftEngine(AnalysisContext ctx) {
        this(ctx, Params.DEFAULT);
    }

    DraftEngine(AnalysisContext ctx, Params params) {
        this.ctx = ctx;
        this.meta = ctx.meta;
        this.params = params;
    }

    // ---------------------------------------------------------------- близость игрок—герой

    /** Всё, что движку нужно знать о команде. Строится один раз на драфт. */
    final class SquadModel {
        final Squad squad;
        final List<PlayerStats> players;
        final Map<Long, Map<Integer, Integer>> tourney;
        final Map<Integer, Integer> bans;
        /** Сколько карт команда сыграла в турнире (без проверяемой в бэктесте). */
        final int maps;
        final Map<Integer, Double> threat = new HashMap<>();
        /** Доли героев за 60 дней до asOf: игрок → герой → {все игры, игры с командой}. */
        final Map<PlayerStats, Map<Integer, double[]>> recent = new HashMap<>();

        SquadModel(Squad squad, long excludeMatchId) {
            this.squad = squad;
            this.players = squad.byPosition();
            this.tourney = tourneyPicksOf(squad, excludeMatchId);
            this.bans = tourneyBansOf(squad, excludeMatchId);
            this.maps = mapsOf(squad, excludeMatchId);
            for (Squad.Threat t : squad.threatList(tourney)) {
                threat.put(t.hero(), t.score());
            }
            long asOf = ctx.league.matches.stream().filter(m -> m.matchId() == excludeMatchId)
                    .mapToLong(League.Match::startTime).findFirst()
                    .orElse(java.time.LocalDate.parse(squad.today).plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond());
            for (PlayerStats p : players) {
                recent.put(p, p.recentShares(asOf, 60));
            }
        }

        /** Насколько вероятно, что игрок p возьмёт героя h (относительный вес, > 0). */
        double affinity(PlayerStats p, int h) {
            int pos = squad.position(p);
            double posFit = meta.posTotal(h) >= 20 ? meta.posShare(h, pos) : 0.1;
            double skill = p.skillOn(h) / 100;
            int t = tourney.getOrDefault(p.config.accountId(), Map.of()).getOrDefault(h, 0);
            double[] r = recent.get(p).getOrDefault(h, NO_RECENT);
            return (0.05 + posFit) * (0.15 + skill) * (1 + 1.5 * t)
                    * (1 + params.shareWeight() * Math.min(share(p, h), params.shareCap()))
                    * (1 + params.recentWeight() * Math.min(r[0], params.shareCap()))
                    * (1 + params.teamWeight() * Math.min(r[1], params.shareCap()));
        }

        int tourneyPicks(PlayerStats p, int h) {
            return tourney.getOrDefault(p.config.accountId(), Map.of()).getOrDefault(h, 0);
        }

        /** Сглаженная доля карт турнира, где команда банила героя: 2 из 2 → 0,67, 4 из 4 → 0,8, 0 из 4 → 0. */
        double banRate(int h) {
            return bans.getOrDefault(h, 0) / (maps + 1.0);
        }

        List<RosterLine> roster() {
            return players.stream().map(p -> new RosterLine(p.nick(), squad.position(p))).toList();
        }
    }

    private static final double[] NO_RECENT = {0, 0};

    /** Доля героя в играх игрока: поровну за год и за текущий патч — «чем играет в последнее время». */
    static double share(PlayerStats p, int h) {
        PlayerStats.HeroLine line = p.heroes.get(h);
        double yearShare = line == null || p.yearGames == 0 ? 0 : (double) line.yearGames / p.yearGames;
        double patchShare = line == null || p.patchGames == 0 ? 0 : (double) line.patchGames / p.patchGames;
        return 0.5 * yearShare + 0.5 * patchShare;
    }

    private Map<Long, Map<Integer, Integer>> tourneyPicksOf(Squad s, long exclude) {
        Map<Long, Map<Integer, Integer>> picks = new HashMap<>();
        if (s.hasTournament()) {
            for (League.Match m : ctx.league.of(s.team.teamId())) {
                if (m.matchId() == exclude) {
                    continue;
                }
                boolean rad = m.isRadiant(s.team.teamId());
                m.side(rad).forEach(p -> picks.computeIfAbsent(p.accountId(), k -> new HashMap<>())
                        .merge(p.heroId(), 1, Integer::sum));
            }
        }
        return picks;
    }

    private Map<Integer, Integer> tourneyBansOf(Squad s, long exclude) {
        Map<Integer, Integer> bans = new HashMap<>();
        if (s.hasTournament()) {
            for (League.Match m : ctx.league.of(s.team.teamId())) {
                if (m.matchId() == exclude) {
                    continue;
                }
                boolean rad = m.isRadiant(s.team.teamId());
                m.draft().stream().filter(d -> !d.pick() && d.radiant() == rad)
                        .forEach(d -> bans.merge(d.heroId(), 1, Integer::sum));
            }
        }
        return bans;
    }

    private int mapsOf(Squad s, long exclude) {
        return !s.hasTournament() ? 0
                : (int) ctx.league.of(s.team.teamId()).stream().filter(m -> m.matchId() != exclude).count();
    }

    /**
     * Назначение «герой → игрок»: перебор всех инъекций (≤ 120), вес назначения — произведение близостей.
     * Возвращает маргинальные вероятности prob[hero index][player index].
     */
    static double[][] assignmentProbabilities(double[][] weight) {
        int heroes = weight.length;
        int players = heroes == 0 ? 0 : weight[0].length;
        double[][] prob = new double[heroes][players];
        if (heroes == 0) {
            return prob;
        }
        double total = 0;
        for (int[] perm : Squad.permutations(indices(players), Math.min(heroes, players))) {
            double w = product(weight, perm);
            total += w;
            for (int h = 0; h < perm.length; h++) {
                prob[h][perm[h]] += w;
            }
        }
        for (double[] row : prob) {
            for (int p = 0; p < players; p++) {
                row[p] = total == 0 ? 0 : row[p] / total;
            }
        }
        return prob;
    }

    /** Самая вероятная расстановка целиком (один игрок — один герой): perm[hero index] = player index. */
    static int[] bestAssignment(double[][] weight) {
        int heroes = weight.length;
        if (heroes == 0) {
            return new int[0];
        }
        int[] best = null;
        double bestW = -1;
        for (int[] perm : Squad.permutations(indices(weight[0].length), Math.min(heroes, weight[0].length))) {
            double w = product(weight, perm);
            if (w > bestW) {
                bestW = w;
                best = perm;
            }
        }
        return best;
    }

    private static int[] indices(int n) {
        int[] all = new int[n];
        for (int i = 0; i < n; i++) {
            all[i] = i;
        }
        return all;
    }

    private static double product(double[][] weight, int[] perm) {
        double w = 1;
        for (int h = 0; h < perm.length; h++) {
            w *= weight[h][perm[h]];
        }
        return w;
    }

    private List<Assignment> assign(SquadModel model, List<Integer> picks, Map<Integer, String> fixed, double[][] probOut) {
        int n = model.players.size();
        double[][] weight = new double[picks.size()][n];
        boolean[] isFixed = new boolean[picks.size()];
        for (int h = 0; h < picks.size(); h++) {
            String nick = fixed.get(picks.get(h));
            int fixedPlayer = -1;
            for (int p = 0; p < n; p++) {
                if (model.players.get(p).nick().equals(nick)) {
                    fixedPlayer = p;
                }
            }
            isFixed[h] = fixedPlayer >= 0;
            for (int p = 0; p < n; p++) {
                weight[h][p] = isFixed[h] ? (p == fixedPlayer ? 1 : 0) : model.affinity(model.players.get(p), picks.get(h));
            }
        }
        double[][] prob = assignmentProbabilities(weight);
        int[] best = bestAssignment(weight);
        for (int h = 0; h < prob.length; h++) {
            System.arraycopy(prob[h], 0, probOut[h], 0, prob[h].length);
        }
        List<Assignment> result = new ArrayList<>();
        for (int h = 0; h < picks.size(); h++) {
            int main = best[h], second = -1;
            for (int p = 0; p < n; p++) {
                if (p != main && (second < 0 || prob[h][p] > prob[h][second])) {
                    second = p;
                }
            }
            boolean hasAlt = second >= 0 && prob[h][second] >= 0.01;
            result.add(new Assignment(picks.get(h), meta.name(picks.get(h)), model.players.get(main).nick(), prob[h][main],
                    hasAlt ? model.players.get(second).nick() : null, hasAlt ? prob[h][second] : 0, isFixed[h]));
        }
        return result;
    }

    /** Вероятность, что игрок ещё без героя: 1 − сумма его вероятностей по уже взятым героям. */
    private static double[] freeProbability(double[][] prob, int players) {
        double[] free = new double[players];
        for (int p = 0; p < players; p++) {
            double taken = 0;
            for (double[] row : prob) {
                taken += row[p];
            }
            free[p] = Math.max(0, 1 - taken);
        }
        return free;
    }

    // ---------------------------------------------------------------- совет

    public Advice advise(State state) {
        Squad oppSquad = ctx.opponent(state.opponentSlug());
        if (oppSquad == null) {
            throw new IllegalArgumentException("нет соперника " + state.opponentSlug());
        }
        return advise(ctx.ours, oppSquad, state.weFirst(), state.heroes(),
                state.assign() == null ? Map.of() : state.assign(), 0);
    }

    Advice advise(Squad usSquad, Squad themSquad, boolean weFirst, List<Integer> heroes, long excludeMatchId) {
        return advise(usSquad, themSquad, weFirst, heroes, Map.of(), excludeMatchId);
    }

    /**
     * Совет для команды usSquad против themSquad (для бэктеста — любые две команды группы).
     * excludeMatchId — не учитывать этот матч в турнирной истории (чтобы не подсматривать ответ).
     */
    Advice advise(Squad usSquad, Squad themSquad, boolean weFirst, List<Integer> heroes, Map<Integer, String> fixed,
                  long excludeMatchId) {
        long started = System.nanoTime();
        SquadModel us = new SquadModel(usSquad, excludeMatchId);
        SquadModel them = new SquadModel(themSquad, excludeMatchId);

        List<Integer> ourPicks = new ArrayList<>(), theirPicks = new ArrayList<>();
        List<Integer> ourBans = new ArrayList<>(), theirBans = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        for (int i = 0; i < heroes.size() && i < SLOTS.size(); i++) {
            int h = heroes.get(i) == null ? 0 : heroes.get(i);
            if (h <= 0) {
                continue;
            }
            Slot slot = SLOTS.get(i);
            boolean ours = slot.firstTeam() == weFirst;
            (slot.pick() ? (ours ? ourPicks : theirPicks) : (ours ? ourBans : theirBans)).add(h);
            used.add(h);
        }
        int step = Math.min(heroes.size(), SLOTS.size());
        boolean finished = step >= SLOTS.size();
        Slot next = finished ? null : SLOTS.get(step);
        boolean oursNext = next != null && next.firstTeam() == weFirst;
        int theirBansLeft = left(step, !weFirst, false), theirPicksLeft = left(step, !weFirst, true);
        int ourBansLeft = left(step, weFirst, false), ourPicksLeft = left(step, weFirst, true);

        double[][] theirProb = new double[theirPicks.size()][them.players.size()];
        List<Assignment> theirAssignment = assign(them, theirPicks, fixed, theirProb);
        double[][] ourProb = new double[ourPicks.size()][us.players.size()];
        List<Assignment> ourAssignment = assign(us, ourPicks, fixed, ourProb);

        double[] theirFree = freeProbability(theirProb, them.players.size());
        Distribution theirDist = pickDistribution(them, theirFree, theirPicks, ourPicks, used);
        List<Reasoned> theirNext = predictPicks(them, theirDist, theirPicks, ourPicks);
        List<Reasoned> ourPickList = suggestPicks(us, ourProb, ourPicks, theirPicks, used, theirDist);
        List<Reasoned> bans = suggestBans(them, us, ourPicks, used, theirDist,
                ourPickList.stream().limit(3).map(Reasoned::heroId).toList());
        List<Reasoned> likelyBans = predictTheirBans(us, them, ourProb, ourPicks, theirPicks, used, theirBansLeft);

        List<Lane> lanes = finished ? lanes(us, them, ourAssignment, theirAssignment) : List.of();
        List<Reasoned> threats = finished ? threats(them, theirAssignment) : List.of();
        return new Advice(step, finished, oursNext, next != null && next.pick(), ourPicks, theirPicks, ourBans, theirBans,
                theirAssignment, ourAssignment, remaining(them, theirProb), remaining(us, ourProb),
                theirNext.stream().limit(10).toList(), bans, ourPickList, likelyBans,
                outlook(them, theirDist), us.roster(), them.roster(),
                theirBansLeft, theirPicksLeft, ourBansLeft, ourPicksLeft, lanes, threats,
                (System.nanoTime() - started) / 1000);
    }

    /**
     * Для бэктеста: распределение «их следующий пик» (сумма 1) после ходов heroes — чтобы считать log-loss.
     */
    Map<Integer, Double> theirNextDistribution(Squad usSquad, Squad themSquad, boolean weFirst, List<Integer> heroes,
                                               long excludeMatchId) {
        SquadModel them = new SquadModel(themSquad, excludeMatchId);
        List<Integer> ourPicks = new ArrayList<>(), theirPicks = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        for (int i = 0; i < heroes.size() && i < SLOTS.size(); i++) {
            int h = heroes.get(i) == null ? 0 : heroes.get(i);
            if (h <= 0) {
                continue;
            }
            Slot slot = SLOTS.get(i);
            boolean ours = slot.firstTeam() == weFirst;
            if (slot.pick()) {
                (ours ? ourPicks : theirPicks).add(h);
            }
            used.add(h);
        }
        double[][] prob = new double[theirPicks.size()][them.players.size()];
        assign(them, theirPicks, Map.of(), prob);
        return pickDistribution(them, freeProbability(prob, them.players.size()), theirPicks, ourPicks, used).next();
    }

    /** Сколько ходов (банов или пиков) осталось у команды начиная с хода step. */
    static int left(int step, boolean firstTeam, boolean pick) {
        int n = 0;
        for (int i = step; i < SLOTS.size(); i++) {
            if (SLOTS.get(i).firstTeam() == firstTeam && SLOTS.get(i).pick() == pick) {
                n++;
            }
        }
        return n;
    }

    private double avgSynergy(int h, List<Integer> team) {
        double sum = 0;
        int n = 0;
        for (int x : team) {
            double lift = meta.synergyLift(h, x, Squad.MIN_PAIR_GAMES);
            if (!Double.isNaN(lift)) {
                sum += lift;
                n++;
            }
        }
        return n == 0 ? 0 : sum / team.size();
    }

    /** Среднее преимущество h против героев enemy (NaN-пары считаются нулём). */
    private double avgCounter(int h, List<Integer> enemy) {
        double sum = 0;
        for (int x : enemy) {
            double adv = meta.counterAdvantage(h, x, Squad.MIN_PAIR_GAMES);
            sum += Double.isNaN(adv) ? 0 : adv;
        }
        return enemy.isEmpty() ? 0 : sum / enemy.size();
    }

    private String bestPair(int h, List<Integer> team, boolean synergy) {
        int best = -1;
        double bestValue = 0.02;
        for (int x : team) {
            double v = synergy ? meta.synergyLift(h, x, Squad.MIN_PAIR_GAMES) : meta.counterAdvantage(h, x, Squad.MIN_PAIR_GAMES);
            if (!Double.isNaN(v) && v >= bestValue) {
                bestValue = v;
                best = x;
            }
        }
        return best < 0 ? null : meta.name(best) + " " + Md.signedPct(bestValue);
    }

    /**
     * Прогноз пиков команды.
     * <ul>
     *   <li>perPlayer[h][p] — вероятность, что герой игрока p — это h (нормировано внутри игрока);</li>
     *   <li>next(h) — вероятность, что h будет их следующим пиком (смесь игроков с весом «насколько свободен»);</li>
     *   <li>chance(h) — шанс, что они вообще возьмут h до конца драфта: сумма по игрокам «свободен × его герой — h».</li>
     * </ul>
     */
    record Distribution(double[] free, Map<Integer, double[]> perPlayer, Map<Integer, Double> next,
                        Map<Integer, Double> chance, Map<Integer, Integer> bestPlayer) {
    }

    /**
     * У каждого игрока, ещё не выбравшего героя, своё распределение по героям (близость × сила в мете × популярность
     * на его ранге × контекст драфта, в степени sharpness), нормированное внутри игрока — иначе игрок с большой
     * турнирной историей «перекрикивает» остальных. Если остался только мидер — почти всё про героев мидера.
     */
    private Distribution pickDistribution(SquadModel team, double[] free, List<Integer> allies, List<Integer> enemies,
                                          Set<Integer> used) {
        int n = team.players.size();
        double freeTotal = 0;
        for (double f : free) {
            freeTotal += f;
        }
        Map<Integer, double[]> perPlayer = new LinkedHashMap<>();
        double[] norm = new double[n];
        for (int h : meta.heroes.keySet()) {
            if (used.contains(h)) {
                continue;
            }
            double context = Math.exp(8 * (avgSynergy(h, allies) + avgCounter(h, enemies)));
            double[] scores = new double[n];
            for (int p = 0; p < n; p++) {
                PlayerStats player = team.players.get(p);
                int[] r = team.squad.bracketRange(player);
                double strength = team.squad.metaStrength(h, team.squad.position(player), r[0], r[1]);
                // популярность героя на ранге игрока: в бэктесте подняла попадание в топ-10 с 47% до 57%
                double popularity = 0.3 + 10 * meta.pickRate(h, r[0], r[1]);
                scores[p] = Math.pow(team.affinity(player, h) * (0.5 + strength) * popularity * context, params.sharpness());
                norm[p] += scores[p];
            }
            perPlayer.put(h, scores);
        }
        Map<Integer, Double> next = new LinkedHashMap<>(), chance = new LinkedHashMap<>();
        Map<Integer, Integer> bestPlayer = new HashMap<>();
        for (var e : perPlayer.entrySet()) {
            double[] s = e.getValue();
            double c = 0, best = -1;
            for (int p = 0; p < n; p++) {
                s[p] = norm[p] == 0 ? 0 : s[p] / norm[p];
                double w = free[p] * s[p];
                c += w;
                if (w > best) {
                    best = w;
                    bestPlayer.put(e.getKey(), p);
                }
            }
            chance.put(e.getKey(), Math.min(1, c));
            next.put(e.getKey(), freeTotal == 0 ? 0 : c / freeTotal);
        }
        Map<Integer, Double> sortedNext = new LinkedHashMap<>();
        next.entrySet().stream().sorted(Map.Entry.<Integer, Double>comparingByValue().reversed())
                .forEach(e -> sortedNext.put(e.getKey(), e.getValue()));
        return new Distribution(free, perPlayer, sortedNext, chance, bestPlayer);
    }

    /** Их следующий пик: score — шанс, что они возьмут героя до конца драфта (порядок — как у «следующего пика»). */
    private List<Reasoned> predictPicks(SquadModel them, Distribution d, List<Integer> theirPicks, List<Integer> ourPicks) {
        List<Reasoned> result = new ArrayList<>();
        for (int h : d.next().keySet()) {
            if (result.size() >= 15) {
                break;
            }
            int best = d.bestPlayer().get(h);
            if (d.free()[best] < 0.15) {
                continue; // герой игрока, который почти наверняка уже выбрал: острый прогноз не должен его вытаскивать
            }
            PlayerStats p = them.players.get(best);
            result.add(new Reasoned(h, meta.name(h), d.chance().get(h), p.nick(),
                    pickReasons(them, p, h, theirPicks, ourPicks, "наш")));
        }
        return result;
    }

    /** Топ героев каждого игрока соперника, который ещё может быть без героя. */
    private List<PlayerOutlook> outlook(SquadModel them, Distribution d) {
        List<PlayerOutlook> result = new ArrayList<>();
        for (int p = 0; p < them.players.size(); p++) {
            if (d.free()[p] < 0.15) {
                continue;
            }
            final int player = p;
            List<HeroOdds> top = d.perPlayer().entrySet().stream()
                    .sorted(Comparator.comparingDouble((Map.Entry<Integer, double[]> e) -> e.getValue()[player]).reversed())
                    .limit(4)
                    .map(e -> new HeroOdds(e.getKey(), meta.name(e.getKey()), e.getValue()[player]))
                    .toList();
            PlayerStats ps = them.players.get(p);
            result.add(new PlayerOutlook(ps.nick(), them.squad.position(ps), d.free()[p], top));
        }
        return result;
    }

    /**
     * Что они забанят у нас — шанс, что герой уйдёт в бан до конца их банов.
     * <ul>
     *   <li>Цели — наши герои: распределение «что взяли бы наши игроки, ещё не выбравшие героя» (турнир, недавние
     *   игры, умение, позиция). Если каждый их оставшийся бан — герой из этого распределения, шанс героя
     *   1 − (1 − p)^бановОсталось. Если наш мид уже взял героя, его пул отсюда уходит.</li>
     *   <li>Их привычка: сглаженная доля карт турнира, где они банили героя (4 из 4 → 0,8). В полную силу — для
     *   героев из пулов наших игроков ({@link Squad#poolOwners()}), для остальных — пропорционально тому, насколько
     *   герой наш (полностью от 2% нашего распределения). Чужие для нас герои (у нас никто не играет) не показываем.</li>
     * </ul>
     */
    private List<Reasoned> predictTheirBans(SquadModel us, SquadModel them, double[][] ourProb, List<Integer> ourPicks,
                                            List<Integer> theirPicks, Set<Integer> used, int bansLeft) {
        if (bansLeft == 0) {
            return List.of();
        }
        double[] free = freeProbability(ourProb, us.players.size());
        Distribution ours = pickDistribution(us, free, ourPicks, theirPicks, used);
        Map<Integer, List<String>> pool = us.squad.poolOwners();
        List<Reasoned> result = new ArrayList<>();
        for (var e : ours.next().entrySet()) {
            int h = e.getKey();
            double p = e.getValue();
            if (p < 0.003) {
                continue;
            }
            double target = 1 - Math.pow(1 - p, bansLeft);
            double habit = them.banRate(h) * (pool.containsKey(h) ? 1 : Math.min(1, p / 0.02));
            double chance = 1 - (1 - target) * (1 - habit);
            PlayerStats player = us.players.get(ours.bestPlayer().get(h));
            List<String> reasons = new ArrayList<>();
            reasons.add("наш " + player.nick());
            int t = us.tourneyPicks(player, h);
            if (t > 0) {
                reasons.add("×" + t + " у нас в турнире");
            }
            int banned = them.bans.getOrDefault(h, 0);
            if (banned > 0) {
                reasons.add("банили " + banned + " из " + them.maps);
            }
            result.add(new Reasoned(h, meta.name(h), chance, player.nick(), reasons));
        }
        result.sort(Comparator.comparingDouble(Reasoned::score).reversed());
        return result.stream().limit(8).toList();
    }

    /** Кто ещё без героя: игрок, его позиция и вероятность, что он ещё не выбрал. */
    public record Remaining(String player, int position, double probability) {
    }

    private List<Remaining> remaining(SquadModel team, double[][] prob) {
        double[] free = freeProbability(prob, team.players.size());
        List<Remaining> result = new ArrayList<>();
        for (int p = 0; p < team.players.size(); p++) {
            if (free[p] >= 0.15) {
                PlayerStats player = team.players.get(p);
                result.add(new Remaining(player.nick(), team.squad.position(player), free[p]));
            }
        }
        return result;
    }

    private List<String> pickReasons(SquadModel model, PlayerStats p, int h, List<Integer> allies, List<Integer> enemies,
                                     String enemyWord) {
        List<String> reasons = new ArrayList<>();
        int t = model.tourneyPicks(p, h);
        if (t > 0) {
            reasons.add("×" + t + " в турнире");
        }
        double skill = p.skillOn(h);
        if (skill >= 60) {
            reasons.add("умение " + Md.num(skill));
        }
        double share = share(p, h);
        if (share >= 0.05) {
            reasons.add(Md.pct(share) + " его игр");
        }
        int[] r = model.squad.bracketRange(p);
        double wr = meta.winrate(h, r[0], r[1]);
        if (wr >= Meta.STRONG_WR) {
            reasons.add("▲ " + Md.pct(wr));
        } else if (wr <= Meta.WEAK_WR) {
            reasons.add("▼ " + Md.pct(wr));
        }
        String syn = bestPair(h, allies, true);
        if (syn != null) {
            reasons.add("связка с " + syn);
        }
        String cnt = bestPair(h, enemies, false);
        if (cnt != null) {
            reasons.add("контрит " + enemyWord + " " + cnt);
        }
        return reasons;
    }

    /**
     * Наши пики: пригодность свободного игрока + связки с нашими + контрпик их пиков − насколько их вероятные
     * следующие пики контрят героя + «пик-отказ» (герой из их топа).
     */
    private List<Reasoned> suggestPicks(SquadModel us, double[][] prob, List<Integer> ourPicks, List<Integer> theirPicks,
                                        Set<Integer> used, Distribution theirs) {
        double[] free = freeProbability(prob, us.players.size());
        List<Map.Entry<Integer, Double>> theirTop = theirs.next().entrySet().stream().limit(8).toList();
        List<Reasoned> result = new ArrayList<>();
        for (int h : meta.heroes.keySet()) {
            if (used.contains(h)) {
                continue;
            }
            PlayerStats bestPlayer = null;
            double bestFit = -1;
            for (int p = 0; p < us.players.size(); p++) {
                PlayerStats player = us.players.get(p);
                int pos = us.squad.position(player);
                if (free[p] < 0.3 || meta.posTotal(h) < 20 || meta.posShare(h, pos) < 0.15) {
                    continue;
                }
                int[] r = us.squad.bracketRange(player);
                double fit = Scoring.patchFit(us.squad.metaStrength(h, pos, r[0], r[1]), player.skillOn(h)) / 100
                        + 0.05 * Math.min(2, us.tourneyPicks(player, h));
                if (fit > bestFit) {
                    bestFit = fit;
                    bestPlayer = player;
                }
            }
            if (bestPlayer == null) {
                continue;
            }
            double vulnerability = 0;
            for (var e : theirTop) {
                double adv = meta.counterAdvantage(e.getKey(), h, Squad.MIN_PAIR_GAMES);
                vulnerability += Double.isNaN(adv) ? 0 : adv * e.getValue();
            }
            double deny = theirs.next().getOrDefault(h, 0.0);
            double score = bestFit + 4 * avgSynergy(h, ourPicks) + 4 * avgCounter(h, theirPicks) - 4 * vulnerability
                    + 0.5 * deny;
            List<String> reasons = pickReasons(us, bestPlayer, h, ourPicks, theirPicks, "их");
            double denyChance = theirs.chance().getOrDefault(h, 0.0);
            if (denyChance >= 0.15) {
                reasons.add("пик-отказ: у них " + Md.pct(denyChance));
            }
            result.add(new Reasoned(h, meta.name(h), 100 * score, bestPlayer.nick(), reasons));
        }
        result.sort(Comparator.comparingDouble(Reasoned::score).reversed());
        return result.stream().limit(10).toList();
    }

    /**
     * Баны: шанс их пика × вред для нас (угроза игрока на герое, контрпик наших пиков, наша плохая история
     * против героя). Герои, которых мы сами хотим взять первыми, не предлагаются — для них «пик-отказ».
     */
    private List<Reasoned> suggestBans(SquadModel them, SquadModel us, List<Integer> ourPicks, Set<Integer> used,
                                       Distribution theirs, List<Integer> ourWanted) {
        Map<Integer, int[]> vs = us.squad.vsEnemies();
        double avg = Squad.average(vs);
        List<Reasoned> result = new ArrayList<>();
        for (var e : theirs.chance().entrySet()) {
            int h = e.getKey();
            if (used.contains(h) || ourWanted.contains(h)) {
                continue;
            }
            double threat = them.threat.getOrDefault(h, 0.0);
            double counter = Math.max(0, avgCounter(h, ourPicks));
            int[] hist = vs.get(h);
            double nemesis = hist == null || hist[0] < 40 ? 0
                    : Scoring.clamp01((avg - Scoring.shrink(hist[1], hist[0], avg, 30)) / 0.08);
            double damage = 0.4 + threat + 6 * counter + 0.3 * nemesis;
            List<String> reasons = new ArrayList<>();
            reasons.add("возьмут: " + Md.pct(e.getValue()) + " (" + them.players.get(theirs.bestPlayer().get(h)).nick() + ")");
            if (threat >= 0.7) {
                reasons.add("угроза " + Md.dec(threat));
            }
            String cnt = bestPair(h, ourPicks, false);
            if (cnt != null) {
                reasons.add("контрит наш " + cnt);
            }
            if (nemesis > 0.3) {
                reasons.add("нам неудобен: " + Md.pct(Scoring.shrink(hist[1], hist[0], avg, 30)));
            }
            result.add(new Reasoned(h, meta.name(h), e.getValue() * damage, null, reasons));
        }
        result.sort(Comparator.comparingDouble(Reasoned::score).reversed());
        return result.stream().limit(8).toList();
    }

    // ---------------------------------------------------------------- итог драфта

    /**
     * Линии по позициям расстановки: наша лёгкая (1+5) против их сложной (3+4), мид на мид, наша сложная против их
     * лёгкой. Преимущество — среднее counterAdvantage наших героев линии против их героев линии.
     */
    private List<Lane> lanes(SquadModel us, SquadModel them, List<Assignment> ours, List<Assignment> theirs) {
        Map<Integer, Integer> ourByPos = byPosition(us, ours), theirByPos = byPosition(them, theirs);
        List<Lane> lanes = new ArrayList<>();
        lanes.add(lane("Наша лёгкая", ourByPos, List.of(1, 5), theirByPos, List.of(3, 4)));
        lanes.add(lane("Мид", ourByPos, List.of(2), theirByPos, List.of(2)));
        lanes.add(lane("Наша сложная", ourByPos, List.of(3, 4), theirByPos, List.of(1, 5)));
        return lanes;
    }

    private Map<Integer, Integer> byPosition(SquadModel team, List<Assignment> assignment) {
        Map<Integer, Integer> result = new HashMap<>();
        for (Assignment a : assignment) {
            team.players.stream().filter(p -> p.nick().equals(a.player())).findFirst()
                    .ifPresent(p -> result.put(team.squad.position(p), a.heroId()));
        }
        return result;
    }

    private Lane lane(String name, Map<Integer, Integer> ours, List<Integer> ourPos, Map<Integer, Integer> theirs,
                      List<Integer> theirPos) {
        List<Integer> a = ourPos.stream().filter(ours::containsKey).map(ours::get).toList();
        List<Integer> b = theirPos.stream().filter(theirs::containsKey).map(theirs::get).toList();
        double sum = 0;
        int n = 0;
        String best = null;
        double bestAbs = 0.02;
        for (int x : a) {
            for (int y : b) {
                double adv = meta.counterAdvantage(x, y, Squad.MIN_PAIR_GAMES);
                if (Double.isNaN(adv)) {
                    continue;
                }
                sum += adv;
                n++;
                if (Math.abs(adv) >= bestAbs) {
                    bestAbs = Math.abs(adv);
                    best = meta.name(x) + (adv > 0 ? " сильнее " : " слабее ") + meta.name(y) + " " + Md.signedPct(adv);
                }
            }
        }
        return new Lane(name, a.stream().map(meta::name).toList(), b.stream().map(meta::name).toList(),
                n == 0 ? 0 : sum / n, best);
    }

    /** Их самые опасные герои в итоговом драфте: угроза игрока на этом герое. */
    private List<Reasoned> threats(SquadModel them, List<Assignment> theirs) {
        return theirs.stream()
                .map(a -> new Reasoned(a.heroId(), a.name(), them.threat.getOrDefault(a.heroId(), 0.0), a.player(), List.of()))
                .sorted(Comparator.comparingDouble(Reasoned::score).reversed())
                .toList();
    }
}
