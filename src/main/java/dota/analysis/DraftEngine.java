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
     * @param orderWeight  насколько верить порядку позиций в пиках лиги (0 — следующий пик у любого свободного игрока
     *                     поровну, 1 — точно как в лиге: первыми берут саппортов, последними керри и мид, 1,5 — чуть острее)
     * @param contextWeight насколько их пик зависит от связок со своими и контрпиков к нашим (exp(вес × lift))
     * @param firstPickWeight вес привычки команды на первом пике (доля карт турнира, где она так начинала)
     * @param banThreat    вес «угрозы наших героев» в прогнозе их банов (степень при числе оставшихся банов)
     * @param banHabit     вес их привычки банить героя в турнире
     * @param banLeague    вес частоты бана героя во всех драфтах лиги
     * @param banPro       вес частоты бана в про-матчах
     *
     * Значения по умолчанию подобраны честным бэктестом на всех командах лиги (2.11, docs/research/draft-backtest.md).
     */
    public record Params(double sharpness, double shareWeight, double shareCap, double recentWeight, double teamWeight,
                         double orderWeight, double contextWeight, double firstPickWeight,
                         double banThreat, double banHabit, double banLeague, double banPro) {
        public static final Params DEFAULT = new Params(1.25, 32, 0.25, 8, 4, ORDER_WEIGHT, 2, 4, 0.5, 1, 2, 0);

        /** Копия с одним изменённым параметром (для подбора в бэктесте). */
        public Params with(String name, double v) {
            return new Params(
                    name.equals("sharpness") ? v : sharpness, name.equals("shareWeight") ? v : shareWeight,
                    name.equals("shareCap") ? v : shareCap, name.equals("recentWeight") ? v : recentWeight,
                    name.equals("teamWeight") ? v : teamWeight, name.equals("orderWeight") ? v : orderWeight,
                    name.equals("contextWeight") ? v : contextWeight, name.equals("firstPickWeight") ? v : firstPickWeight,
                    name.equals("banThreat") ? v : banThreat, name.equals("banHabit") ? v : banHabit,
                    name.equals("banLeague") ? v : banLeague, name.equals("banPro") ? v : banPro);
        }
    }

    /** Вес порядка позиций по умолчанию (подобран бэктестом 2.11). */
    static final double ORDER_WEIGHT = 1.5;

    /**
     * heroes[i] — герой хода i (0 — ход пропущен). assign — ручное назначение «герой → ник игрока»
     * (капитан знает, кто на каком герое, лучше статистики), для обеих команд.
     */
    public record State(String opponentSlug, boolean weFirst, List<Integer> heroes, Map<Integer, String> assign,
                        String lineup, Set<Integer> never) {
        public State(String opponentSlug, boolean weFirst, List<Integer> heroes, Map<Integer, String> assign,
                     String lineup) {
            this(opponentSlug, weFirst, heroes, assign, lineup, Set.of());
        }

        public State(String opponentSlug, boolean weFirst, List<Integer> heroes, Map<Integer, String> assign) {
            this(opponentSlug, weFirst, heroes, assign, null);
        }

        public State(String opponentSlug, boolean weFirst, List<Integer> heroes) {
            this(opponentSlug, weFirst, heroes, Map.of(), null);
        }
    }

    /**
     * Состав соперника из турнира и его вес сейчас: равные до их пиков, дальше по тому, насколько их пики похожи на
     * героев этого состава. Выбранный вручную состав — вес 1.
     */
    public record LineupOdds(String key, String label, int maps, String last, double weight) {
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

    /**
     * «Кого взять на линию»: наш игрок ещё без героя, а герои соперника на его линии уже известны. heroes — его герои
     * (на которых он реально играет), лучшие против них на линии и по игре.
     */
    public record LaneOption(String player, int position, List<String> enemies, List<Reasoned> heroes) {
    }

    /** Пара на двойной пик (два наших пика подряд): два героя разным игрокам, связка между ними. */
    public record PairPick(int a, String aName, String aPlayer, int b, String bName, String bPlayer, double score,
                           String note) {
    }

    public record Advice(int step, boolean finished, boolean oursNext, boolean pickNext, List<Integer> ourPicks,
                         List<Integer> theirPicks, List<Integer> ourBans, List<Integer> theirBans,
                         List<Assignment> theirAssignment, List<Assignment> ourAssignment,
                         List<Remaining> theirRemaining, List<Remaining> ourRemaining,
                         List<Reasoned> theirNextPicks, List<Reasoned> banSuggestions,
                         List<Reasoned> pickSuggestions, List<Reasoned> theirLikelyBans,
                         List<PlayerOutlook> theirPlayers, List<RosterLine> ourRoster, List<RosterLine> theirRoster,
                         int theirBansLeft, int theirPicksLeft, int ourBansLeft, int ourPicksLeft,
                         List<Lane> lanes, List<Reasoned> theirThreats, List<LaneOption> laneOptions,
                         List<PairPick> pairPicks, List<PositionBans> positionBans,
                         List<LineupOdds> theirLineups, String theirLineup, long micros) {

        /** Тот же совет, но баны, прогноз пиков и баны по позициям — по всем составам соперника сразу. */
        Advice merged(List<Reasoned> bans, List<Reasoned> next, List<PositionBans> positions, List<LineupOdds> lineups,
                      String lineup, long micros) {
            return new Advice(step, finished, oursNext, pickNext, ourPicks, theirPicks, ourBans, theirBans,
                    theirAssignment, ourAssignment, theirRemaining, ourRemaining, next, bans, pickSuggestions,
                    theirLikelyBans, theirPlayers, ourRoster, theirRoster, theirBansLeft, theirPicksLeft, ourBansLeft,
                    ourPicksLeft, lanes, theirThreats, laneOptions, pairPicks, positions, lineups, lineup, micros);
        }
    }

    /**
     * Баны по позиции соперника: лучшие баны против его игрока на этой позиции. Остаются и после его пика — герои
     * могут свапнуться (керри отдаст героя в мид и возьмёт другого). picked — герой, которого игрок вероятнее всего
     * уже взял (или null). share — вероятность, что на позиции именно он (несколько составов соперника), иначе 1.
     */
    public record PositionBans(int position, String player, Integer picked, String pickedName, List<Reasoned> heroes,
                               double share) {
    }

    /** Героев в строке позиции: сайт показывает 5, остальные по кнопке «ещё». */
    static final int POSITION_BANS = 10;

    private final AnalysisContext ctx;
    private final Meta meta;
    private final Params params;
    /** order[k][pos] — доля позиции pos среди (k+1)-х пиков команд в матчах лиги (позиции по линиям и золоту). */
    private final double[][] order;

    public DraftEngine(AnalysisContext ctx) {
        this(ctx, Params.DEFAULT);
    }

    DraftEngine(AnalysisContext ctx, Params params) {
        this.ctx = ctx;
        this.meta = ctx.meta;
        this.params = params;
        this.order = pickOrder(ctx.league);
    }

    /**
     * Какие позиции команды закрывают k-м пиком (по всем картам лиги со стандартным порядком). В лиге первые два пика
     * в 65–72% саппорты, последний в 81% керри или мид. Сглажено к равномерному: 20 карт на каждый пик.
     */
    static double[][] pickOrder(League league) {
        double[][] count = new double[5][6];
        if (league != null) {
            for (League.Match m : league.matches) {
                for (boolean radiant : new boolean[]{true, false}) {
                    Map<Integer, Long> lineup = TeamLineups.positions(m.side(radiant));
                    if (lineup == null) {
                        continue;
                    }
                    Map<Integer, Integer> posOfHero = new HashMap<>();
                    for (League.Player p : m.side(radiant)) {
                        lineup.forEach((pos, id) -> {
                            if (id == p.accountId()) {
                                posOfHero.put(p.heroId(), pos);
                            }
                        });
                    }
                    int k = 0;
                    for (League.DraftStep s : m.draft()) {
                        if (s.pick() && s.radiant() == radiant && k < 5) {
                            Integer pos = posOfHero.get(s.heroId());
                            if (pos != null) {
                                count[k][pos]++;
                            }
                            k++;
                        }
                    }
                }
            }
        }
        double[][] result = new double[5][6];
        for (int k = 0; k < 5; k++) {
            double total = 0;
            for (int pos = 1; pos <= 5; pos++) {
                total += count[k][pos];
            }
            for (int pos = 1; pos <= 5; pos++) {
                result[k][pos] = (count[k][pos] + 20 * 0.2) / (total + 20);
            }
        }
        return result;
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
        /** Первые пики команды в турнире: герой → сколько раз. */
        final Map<Integer, Integer> firstPicks;
        /** Момент драфта: турнирная история и недавние игры — только до него (в бэктесте — время карты). */
        final long asOf;
        final long exclude;
        final Map<Integer, Double> threat = new HashMap<>();
        /** Доли героев за 60 дней до asOf: игрок → герой → {все игры, игры с командой}. */
        final Map<PlayerStats, Map<Integer, double[]>> recent = new HashMap<>();

        SquadModel(Squad squad, long excludeMatchId) {
            this.squad = squad;
            this.players = squad.byPosition();
            this.exclude = excludeMatchId;
            this.asOf = ctx.league == null ? Long.MAX_VALUE : ctx.league.matches.stream().filter(m -> m.matchId() == excludeMatchId)
                    .mapToLong(League.Match::startTime).findFirst()
                    .orElse(java.time.LocalDate.parse(squad.today).plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond());
            List<League.Match> history = tourneyMaps(squad, excludeMatchId, asOf);
            this.tourney = tourneyPicksOf(squad, history);
            this.bans = tourneyBansOf(squad, history);
            this.maps = history.size();
            this.firstPicks = firstPicksOf(squad, history);
            for (Squad.Threat t : squad.threatList(tourney)) {
                threat.put(t.hero(), t.score());
            }
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

    /** Карты команды в турнире до момента asOf, кроме exclude (в бэктесте — без проверяемой и будущих карт). */
    private List<League.Match> tourneyMaps(Squad s, long exclude, long asOf) {
        if (!s.hasTournament()) {
            return List.of();
        }
        return ctx.league.of(s.team.teamId()).stream().filter(m -> m.matchId() != exclude && m.startTime() < asOf).toList();
    }

    private static Map<Integer, Integer> firstPicksOf(Squad s, List<League.Match> history) {
        Map<Integer, Integer> result = new HashMap<>();
        for (League.Match m : history) {
            boolean rad = m.isRadiant(s.team.teamId());
            m.draft().stream().filter(d -> d.pick() && d.radiant() == rad).findFirst()
                    .ifPresent(d -> result.merge(d.heroId(), 1, Integer::sum));
        }
        return result;
    }

    private static Map<Long, Map<Integer, Integer>> tourneyPicksOf(Squad s, List<League.Match> history) {
        Map<Long, Map<Integer, Integer>> picks = new HashMap<>();
        if (s.hasTournament()) {
            for (League.Match m : history) {
                boolean rad = m.isRadiant(s.team.teamId());
                m.side(rad).forEach(p -> picks.computeIfAbsent(p.accountId(), k -> new HashMap<>())
                        .merge(p.heroId(), 1, Integer::sum));
            }
        }
        return picks;
    }

    private static Map<Integer, Integer> tourneyBansOf(Squad s, List<League.Match> history) {
        Map<Integer, Integer> bans = new HashMap<>();
        if (s.hasTournament()) {
            for (League.Match m : history) {
                boolean rad = m.isRadiant(s.team.teamId());
                m.draft().stream().filter(d -> !d.pick() && d.radiant() == rad)
                        .forEach(d -> bans.merge(d.heroId(), 1, Integer::sum));
            }
        }
        return bans;
    }

    /**
     * Доля сторон в драфтах всей лиги (все дивизионы) до момента asOf, где банили героя h, кроме карты exclude:
     * «что сейчас банят в лиге».
     */
    double leagueBanRate(int h, long exclude, long asOf) {
        if (ctx.league == null) {
            return 0;
        }
        int sides = 0, banned = 0;
        for (League.Match m : ctx.league.matches) {
            if (m.matchId() == exclude || m.startTime() >= asOf || m.draft().isEmpty()) {
                continue;
            }
            sides += 2;
            for (League.DraftStep d : m.draft()) {
                banned += !d.pick() && d.heroId() == h ? 1 : 0;
            }
        }
        return sides == 0 ? 0 : (double) banned / (sides + 10);
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

    List<Assignment> assign(SquadModel model, List<Integer> picks, Map<Integer, String> fixed, double[][] probOut) {
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
    static double[] freeProbability(double[][] prob, int players) {
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
        Map<Integer, String> fixed = state.assign() == null ? Map.of() : state.assign();
        Set<Integer> never = neverPick(state.never());
        List<AnalysisContext.TeamLineup> lineups = ctx.lineupsOf(oppSquad);
        if (lineups.isEmpty()) {
            // один состав в турнире: позиции из карт турнира, а не из конфига (бэктест: «кто на герое» 82% → 88%)
            Squad last = ctx.lastLineup(oppSquad);
            return advise(ctx.ours, last == null ? oppSquad : last, state.weFirst(), state.heroes(), fixed, 0, never);
        }
        // несколько составов (замена, игроки меняются позициями): совет по каждому, баны — по всем сразу с весами
        long started = System.nanoTime();
        double[] w = lineupWeights(lineups, state.lineup(), state.weFirst(), state.heroes(), fixed);
        int best = 0;
        List<Advice> each = new ArrayList<>();
        List<LineupOdds> odds = new ArrayList<>();
        for (int i = 0; i < lineups.size(); i++) {
            AnalysisContext.TeamLineup l = lineups.get(i);
            each.add(w[i] > 0 ? advise(ctx.ours, l.squad(), state.weFirst(), state.heroes(), fixed, 0, never) : null);
            odds.add(new LineupOdds(l.key(), l.label(), l.maps(), l.last(), w[i]));
            if (w[i] > w[best]) {
                best = i;
            }
        }
        Advice primary = each.get(best);
        return primary.merged(mergeReasoned(each, w, Advice::banSuggestions, primary.banSuggestions().size()),
                mergeReasoned(each, w, Advice::theirNextPicks, primary.theirNextPicks().size()),
                mergePositionBans(each, w), odds, lineups.get(best).key(), (System.nanoTime() - started) / 1000);
    }

    /** Насколько уверенно пики сдвигают вес составов: 0,5 — мягко (одного пика мало, чтобы отбросить состав). */
    static final double LINEUP_EVIDENCE = 0.5;
    /** Вес состава не ниже 5%: пока лобби не видно, к нему всё равно готовимся. */
    static final double LINEUP_FLOOR = 0.05;

    /**
     * Веса составов. Выбран вручную (key) — только он. Иначе: составы без игрока, которому капитан вручную отдал их
     * героя, отпадают; остальные — по правдоподобию их пиков (насколько герои похожи на игроков этого состава на
     * этих позициях) в степени LINEUP_EVIDENCE, не ниже LINEUP_FLOOR.
     */
    double[] lineupWeights(List<AnalysisContext.TeamLineup> lineups, String chosen, boolean weFirst, List<Integer> heroes,
                           Map<Integer, String> fixed) {
        return lineupWeights(lineups.stream().map(AnalysisContext.TeamLineup::squad).toList(),
                lineups.stream().map(AnalysisContext.TeamLineup::key).toList(), chosen, weFirst, heroes, fixed, 0,
                recencyPrior(lineups.size()));
    }

    /**
     * Априорный вес составов, отсортированных от свежего: каждый следующий вдвое реже (1, 0,5, 0,25…). Бэктест: «кто на
     * герое» 82% → 88% против «все поровну».
     */
    static double[] recencyPrior(int n) {
        double[] prior = new double[n];
        for (int i = 0; i < n; i++) {
            prior[i] = Math.pow(0.5, i);
        }
        return prior;
    }

    /** То же для списка команд-составов; excludeMatchId — матч, которого нет в турнирной истории (бэктест). */
    double[] lineupWeights(List<Squad> squads, List<String> keys, String chosen, boolean weFirst, List<Integer> heroes,
                           Map<Integer, String> fixed, long excludeMatchId) {
        return lineupWeights(squads, keys, chosen, weFirst, heroes, fixed, excludeMatchId, null);
    }

    /** prior — априорные веса составов (null — поровну), например «свежий состав вероятнее». */
    double[] lineupWeights(List<Squad> squads, List<String> keys, String chosen, boolean weFirst, List<Integer> heroes,
                           Map<Integer, String> fixed, long excludeMatchId, double[] prior) {
        int n = squads.size();
        double[] w = new double[n];
        for (int i = 0; i < n; i++) {
            if (keys.get(i).equals(chosen)) {
                w[i] = 1;
                return w;
            }
        }
        List<Integer> theirPicks = new ArrayList<>();
        for (int i = 0; i < heroes.size() && i < SLOTS.size(); i++) {
            Integer h = heroes.get(i);
            if (h != null && h > 0 && SLOTS.get(i).pick() && SLOTS.get(i).firstTeam() != weFirst) {
                theirPicks.add(h);
            }
        }
        double total = 0;
        for (int i = 0; i < n; i++) {
            Squad sq = squads.get(i);
            Set<String> nicks = new HashSet<>();
            sq.byPosition().forEach(p -> nicks.add(p.nick()));
            boolean fits = theirPicks.stream().map(fixed::get).filter(java.util.Objects::nonNull).allMatch(nicks::contains);
            w[i] = fits ? Math.pow(pickLikelihood(new SquadModel(sq, excludeMatchId), theirPicks), LINEUP_EVIDENCE) : 0;
            w[i] *= prior == null ? 1 : prior[i];
            total += w[i];
        }
        if (!(total > 0)) {
            java.util.Arrays.fill(w, 1);
            total = n;
        }
        double sum = 0;
        for (int i = 0; i < n; i++) {
            w[i] = w[i] == 0 ? 0 : Math.max(LINEUP_FLOOR, w[i] / total);
            sum += w[i];
        }
        for (int i = 0; i < n; i++) {
            w[i] /= sum;
        }
        return w;
    }

    /**
     * Правдоподобие пиков для состава: сумма по расстановкам «герой → свой игрок» произведений долей «игрок берёт этого
     * героя» (близость героя к игроку на его позиции, нормированная по всем героям).
     */
    double pickLikelihood(SquadModel m, List<Integer> picks) {
        if (picks.isEmpty()) {
            return 1;
        }
        int n = m.players.size();
        double[][] share = new double[picks.size()][n];
        for (int p = 0; p < n; p++) {
            PlayerStats player = m.players.get(p);
            double total = 0;
            for (int h : meta.heroes.keySet()) {
                total += m.affinity(player, h);
            }
            for (int i = 0; i < picks.size(); i++) {
                share[i][p] = total > 0 ? m.affinity(player, picks.get(i)) / total : 0;
            }
        }
        double sum = 0;
        for (int[] perm : Squad.permutations(indices(n), Math.min(picks.size(), n))) {
            double prod = 1;
            for (int i = 0; i < perm.length; i++) {
                prod *= share[i][perm[i]];
            }
            sum += prod;
        }
        return sum;
    }

    /**
     * Список по всем составам: очки героя — сумма «вес состава × очки в его совете», причины — из совета, где вклад
     * больше. Если герой важен не при всех составах, это видно в причинах.
     */
    static List<Reasoned> mergeReasoned(List<Advice> each, double[] w,
                                        java.util.function.Function<Advice, List<Reasoned>> list, int limit) {
        Map<Integer, Double> score = new HashMap<>(), weight = new HashMap<>(), top = new HashMap<>();
        Map<Integer, Reasoned> best = new HashMap<>();
        for (int i = 0; i < each.size(); i++) {
            if (each.get(i) == null) {
                continue;
            }
            for (Reasoned r : list.apply(each.get(i))) {
                double c = w[i] * r.score();
                score.merge(r.heroId(), c, Double::sum);
                weight.merge(r.heroId(), w[i], Double::sum);
                if (c >= top.getOrDefault(r.heroId(), Double.NEGATIVE_INFINITY)) {
                    top.put(r.heroId(), c);
                    best.put(r.heroId(), r);
                }
            }
        }
        return score.entrySet().stream()
                .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed())
                .limit(limit)
                .map(e -> {
                    Reasoned r = best.get(e.getKey());
                    List<String> reasons = new ArrayList<>(r.reasons());
                    double share = weight.get(e.getKey());
                    if (share < 0.75) {
                        reasons.add("не при всех составах: " + Md.pct(share));
                    }
                    return new Reasoned(r.heroId(), r.name(), e.getValue(), r.player(), reasons);
                })
                .toList();
    }

    /**
     * Баны по позициям по всем составам: строка на каждую пару «позиция — игрок» с вероятностью, что на позиции он
     * (сумма весов составов, где он там). Герои строки — из самого вероятного такого состава.
     */
    static List<PositionBans> mergePositionBans(List<Advice> each, double[] w) {
        Map<String, PositionBans> rows = new LinkedHashMap<>();
        Map<String, Double> share = new HashMap<>(), top = new HashMap<>();
        for (int i = 0; i < each.size(); i++) {
            if (each.get(i) == null) {
                continue;
            }
            for (PositionBans pb : each.get(i).positionBans()) {
                String k = pb.position() + "|" + pb.player();
                share.merge(k, w[i], Double::sum);
                if (w[i] > top.getOrDefault(k, -1.0)) {
                    top.put(k, w[i]);
                    rows.put(k, pb);
                }
            }
        }
        return rows.entrySet().stream()
                .map(e -> {
                    PositionBans pb = e.getValue();
                    return new PositionBans(pb.position(), pb.player(), pb.picked(), pb.pickedName(), pb.heroes(),
                            Math.min(1, share.get(e.getKey())));
                })
                .sorted(Comparator.comparingInt(PositionBans::position)
                        .thenComparing(Comparator.comparingDouble(PositionBans::share).reversed()))
                .toList();
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
        return advise(usSquad, themSquad, weFirst, heroes, fixed, excludeMatchId, Set.of());
    }

    /** Герои «не берём»: из конфига (never_pick у нашей команды) и отмеченные на сайте. */
    Set<Integer> neverPick(Set<Integer> fromSite) {
        Set<Integer> result = new HashSet<>(fromSite == null ? Set.of() : fromSite);
        if (ctx.config.ourTeam() != null) {
            for (String name : ctx.config.ourTeam().neverPickOrEmpty()) {
                meta.heroes.values().stream().filter(h -> h.name().equalsIgnoreCase(name.trim()))
                        .forEach(h -> result.add(h.id()));
            }
        }
        return result;
    }

    /** never — герои, которых мы не берём: их нет в «Нам брать», двойном пике и «На линию» (баны и их пики как раньше). */
    Advice advise(Squad usSquad, Squad themSquad, boolean weFirst, List<Integer> heroes, Map<Integer, String> fixed,
                  long excludeMatchId, Set<Integer> never) {
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
        Map<Integer, Integer> theirLaners = knownPositions(them, theirAssignment);
        List<Reasoned> ourPickList = suggestPicks(us, ourProb, ourPicks, theirPicks, used, theirDist, theirLaners).stream()
                .filter(r -> !never.contains(r.heroId())).toList();
        Set<Integer> notForUs = new HashSet<>(used);
        notForUs.addAll(never);
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
                finished ? List.of() : laneOptions(us, ourProb, notForUs, theirLaners),
                doublePickNow(step, weFirst) ? pairPicks(us, ourPickList) : List.of(),
                finished ? List.of() : positionBans(them, us, ourPicks, used, theirDist, theirAssignment),
                List.of(), null, (System.nanoTime() - started) / 1000);
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

    /** Для бэктеста: прогноз без остального совета — их следующие пики, распределение, кто на каком герое, их баны. */
    record Forecast(List<Integer> next, Map<Integer, Double> distribution, List<Assignment> assignment,
                    List<Integer> likelyBans) {
    }

    Forecast forecast(Squad usSquad, Squad themSquad, boolean weFirst, List<Integer> heroes, long excludeMatchId,
                      boolean withBans) {
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
        List<Assignment> assignment = assign(them, theirPicks, Map.of(), prob);
        Distribution d = pickDistribution(them, freeProbability(prob, them.players.size()), theirPicks, ourPicks, used);
        List<Integer> next = predictPicks(them, d, theirPicks, ourPicks).stream().map(Reasoned::heroId).toList();
        List<Integer> bans = List.of();
        if (withBans) {
            SquadModel us = new SquadModel(usSquad, excludeMatchId);
            double[][] ourProb = new double[ourPicks.size()][us.players.size()];
            assign(us, ourPicks, Map.of(), ourProb);
            int step = Math.min(heroes.size(), SLOTS.size());
            bans = predictTheirBans(us, them, ourProb, ourPicks, theirPicks, used, left(step, !weFirst, false)).stream()
                    .map(Reasoned::heroId).toList();
        }
        return new Forecast(next, d.next(), assignment, bans);
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

    double avgSynergy(int h, List<Integer> team) {
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
    double avgCounter(int h, List<Integer> enemy) {
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
    Distribution pickDistribution(SquadModel team, double[] free, List<Integer> allies, List<Integer> enemies,
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
            double context = Math.exp(params.contextWeight() * (avgSynergy(h, allies) + avgCounter(h, enemies)));
            if (allies.isEmpty() && params.firstPickWeight() > 0) {
                // привычка команды на первом пике (SyberStar: Dragon Knight в 3 картах из 6)
                context *= 1 + params.firstPickWeight() * team.firstPicks.getOrDefault(h, 0) / (team.maps + 1.0);
            }
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
        // кто возьмёт следующий пик: свободный игрок × (порядок позиций в лиге)^orderWeight
        int k = Math.min(allies.size(), 4);
        double[] nextWeight = new double[n];
        double nextTotal = 0;
        for (int p = 0; p < n; p++) {
            int pos = team.squad.position(team.players.get(p));
            double prior = pos >= 1 && pos <= 5 ? order[k][pos] / 0.2 : 1;
            nextWeight[p] = free[p] * Math.pow(prior, params.orderWeight());
            nextTotal += nextWeight[p];
        }
        Map<Integer, Double> next = new LinkedHashMap<>(), chance = new LinkedHashMap<>();
        Map<Integer, Integer> bestPlayer = new HashMap<>();
        for (var e : perPlayer.entrySet()) {
            double[] s = e.getValue();
            double c = 0, nx = 0, best = -1;
            for (int p = 0; p < n; p++) {
                s[p] = norm[p] == 0 ? 0 : s[p] / norm[p];
                double w = free[p] * s[p];
                c += w;
                nx += nextWeight[p] * s[p];
                if (w > best) {
                    best = w;
                    bestPlayer.put(e.getKey(), p);
                }
            }
            chance.put(e.getKey(), Math.min(1, c));
            next.put(e.getKey(), nextTotal == 0 ? 0 : nx / nextTotal);
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
            double league = params.banLeague() == 0 ? 0 : Math.min(1, params.banLeague() * leagueBanRate(h, them.exclude, them.asOf));
            double pro = params.banPro() == 0 ? 0 : Math.min(1, params.banPro() * meta.proBanRate(h));
            if (p < 0.003 && league < 0.1 && pro < 0.1) {
                continue;
            }
            double target = 1 - Math.pow(1 - p, bansLeft * params.banThreat());
            double habit = Math.min(1, params.banHabit() * them.banRate(h) * (pool.containsKey(h) ? 1 : Math.min(1, p / 0.02)));
            double chance = 1 - (1 - target) * (1 - habit) * (1 - league) * (1 - pro);
            PlayerStats player = us.players.get(ours.bestPlayer().get(h));
            List<String> reasons = new ArrayList<>();
            if (p >= 0.003 || pool.containsKey(h)) {
                reasons.add("наш " + player.nick());
            }
            int t = us.tourneyPicks(player, h);
            if (t > 0) {
                reasons.add("×" + t + " у нас в турнире");
            }
            int banned = them.bans.getOrDefault(h, 0);
            if (banned > 0) {
                reasons.add("банили " + banned + " из " + them.maps);
            }
            if (league / params.banLeague() >= 0.1) {
                reasons.add("часто банят в лиге: " + Md.pct(league / params.banLeague()) + " драфтов");
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

    /**
     * Причины пика: сначала то, что относится к этому драфту (связка с их или нашими героями, контрпик), потом статистика
     * игрока и героя. Капитан видит первые причины сразу, остальные — по клику.
     */
    private List<String> pickReasons(SquadModel model, PlayerStats p, int h, List<Integer> allies, List<Integer> enemies,
                                     String enemyWord) {
        List<String> reasons = new ArrayList<>(draftReasons(h, allies, enemies, enemyWord));
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
        return reasons;
    }

    private List<String> draftReasons(int h, List<Integer> allies, List<Integer> enemies, String enemyWord) {
        List<String> reasons = new ArrayList<>();
        String cnt = bestPair(h, enemies, false);
        if (cnt != null) {
            reasons.add("контрит " + enemyWord + " " + cnt);
        }
        String syn = bestPair(h, allies, true);
        if (syn != null) {
            reasons.add("связка с " + syn);
        }
        return reasons;
    }

    /**
     * Наши пики: пригодность свободного игрока + связки с нашими + контрпик их пиков − насколько их вероятные
     * следующие пики контрят героя + «пик-отказ» (герой из их топа).
     */
    private List<Reasoned> suggestPicks(SquadModel us, double[][] prob, List<Integer> ourPicks, List<Integer> theirPicks,
                                        Set<Integer> used, Distribution theirs, Map<Integer, Integer> theirLaners) {
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
                if (free[p] < 0.3 || meta.posTotal(h) < 20 || meta.posShare(h, pos) < 0.15 || !plays(us, player, h)) {
                    continue;
                }
                int[] r = us.squad.bracketRange(player);
                LaneStats.Outcome lane = laneVs(h, pos, theirLaners);
                double fit = Scoring.patchFit(us.squad.metaStrength(h, pos, r[0], r[1]), player.skillOn(h)) / 100
                        + 0.05 * Math.min(2, us.tourneyPicks(player, h))
                        + LANE_WEIGHT * (lane == null ? 0 : lane.advantage());
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
            String flex = flexNote(us, h, free);
            if (flex != null) {
                reasons.add(flex);
            }
            String laneNote = laneNote(h, us.squad.position(bestPlayer), theirLaners);
            if (laneNote != null) {
                reasons.add(0, laneNote);
            }
            double denyChance = theirs.chance().getOrDefault(h, 0.0);
            if (denyChance >= 0.15) {
                // сразу после причин этого драфта: линия, контрпик, связка
                int at = (laneNote != null ? 1 : 0) + draftReasons(h, ourPicks, theirPicks, "их").size();
                reasons.add(at, "пик-отказ: у них " + Md.pct(denyChance));
            }
            result.add(new Reasoned(h, meta.name(h), 100 * score, bestPlayer.nick(), reasons));
        }
        result.sort(Comparator.comparingDouble(Reasoned::score).reversed());
        return result.stream().limit(10).toList();
    }

    /** Сейчас наш пик и следующий ход тоже наш пик (в CM: ходы 14–15 или 16–17). */
    static boolean doublePickNow(int step, boolean weFirst) {
        return step + 1 < SLOTS.size() && SLOTS.get(step).pick() && SLOTS.get(step + 1).pick()
                && SLOTS.get(step).firstTeam() == weFirst && SLOTS.get(step + 1).firstTeam() == weFirst;
    }

    /** Связка по винрейту пары засчитывается с весом n / (n + PAIR_PRIOR): на 300 играх — наполовину. */
    static final double PAIR_PRIOR = 300;
    /** Связка по винрейту меньше 1,5% (после поправки на выборку) — шум, в подсказке её нет. */
    static final double PAIR_MIN_LIFT = 0.015;

    /**
     * Двойной пик: пары из 12 первых советов разным игрокам. Оценка — сумма оценок героев + связка: винрейт пары в
     * патче с поправкой на число игр (×400), герои на одной линии (+12), сочетание по свойствам героев (+8 за каждое:
     * контроль + урон, оба для драк, пуш вдвоём). Один герой — не больше чем в одной паре: три разных варианта.
     * В подсказке видно, есть ли у пары настоящая связка или это просто два сильных пика.
     */
    private List<PairPick> pairPicks(SquadModel us, List<Reasoned> picks) {
        List<Reasoned> top = picks.stream().limit(12).toList();
        Map<String, Integer> posOf = new HashMap<>();
        us.players.forEach(p -> posOf.put(p.nick(), us.squad.position(p)));
        List<PairPick> all = new ArrayList<>();
        for (int i = 0; i < top.size(); i++) {
            for (int j = i + 1; j < top.size(); j++) {
                Reasoned x = top.get(i), y = top.get(j);
                if (x.player() == null || y.player() == null || x.player().equals(y.player())) {
                    continue;
                }
                List<String> notes = new ArrayList<>();
                double bonus = 0;
                Meta.PairStats s = meta.synergyStats(x.heroId(), y.heroId());
                double lift = meta.synergyLift(x.heroId(), y.heroId(), Squad.MIN_PAIR_GAMES);
                if (!Double.isNaN(lift) && s != null) {
                    double shrunk = lift * s.games() / (s.games() + PAIR_PRIOR);
                    bonus += 400 * shrunk;
                    if (shrunk >= PAIR_MIN_LIFT) {
                        notes.add("вместе выигрывают чаще: " + Md.signedPct(lift) + " (" + s.games() + " игр)");
                    }
                }
                String lane = laneDuo(posOf.getOrDefault(x.player(), 0), posOf.getOrDefault(y.player(), 0));
                if (lane != null) {
                    bonus += 12;
                    notes.add(lane);
                }
                for (String combo : traitCombos(x.heroId(), y.heroId())) {
                    bonus += 8;
                    notes.add(combo);
                }
                if (notes.isEmpty()) {
                    notes.add("особой связки нет: просто два сильных пика");
                }
                all.add(new PairPick(x.heroId(), x.name(), x.player(), y.heroId(), y.name(), y.player(),
                        x.score() + y.score() + bonus, String.join(" · ", notes)));
            }
        }
        all.sort(Comparator.comparingDouble(PairPick::score).reversed());
        List<PairPick> result = new ArrayList<>();
        Set<Integer> taken = new HashSet<>();
        for (PairPick p : all) {
            if (result.size() < 3 && !taken.contains(p.a()) && !taken.contains(p.b())) {
                result.add(p);
                taken.add(p.a());
                taken.add(p.b());
            }
        }
        return result;
    }

    /** Наши позиции стоят на одной линии: лёгкая 1+5, сложная 3+4. */
    static String laneDuo(int a, int b) {
        int lo = Math.min(a, b), hi = Math.max(a, b);
        if (lo == 1 && hi == 5) {
            return "вместе на лёгкой линии";
        }
        if (lo == 3 && hi == 4) {
            return "вместе на сложной линии";
        }
        return null;
    }

    /** Сочетания по config/hero_traits.yaml: контроль одного + урон другого, оба для драк, оба пушат. */
    List<String> traitCombos(int a, int b) {
        HeroTraits t = ctx.traits;
        if (!t.has(a) || !t.has(b)) {
            return List.of();
        }
        HeroTraits.Traits x = t.of(a), y = t.of(b);
        List<String> result = new ArrayList<>();
        if ((x.get("catch") >= 2 && y.get("burst") >= 1) || (y.get("catch") >= 2 && x.get("burst") >= 1)) {
            result.add("контроль + урон");
        }
        if (x.get("teamfight") >= 2 && y.get("teamfight") >= 2) {
            result.add("оба сильны в драках");
        }
        if (x.get("push") >= 2 && y.get("push") >= 1 || y.get("push") >= 2 && x.get("push") >= 1) {
            result.add("пушат вместе");
        }
        return result;
    }

    /** Вес преимущества на линии в оценке нашего пика: линия +0,3 (выиграно на 30% линий больше) даёт +0,15. */
    static final double LANE_WEIGHT = 0.5;

    /**
     * Флекс-герой (FEAT-05): играется на 2+ позициях (≥ 25% матчей патча на каждой) и у двух наших свободных игроков
     * этих позиций есть опыт на нём — можно взять раньше и решить позицию позже, соперник не угадает линию.
     */
    private String flexNote(SquadModel us, int h, double[] free) {
        if (meta.posTotal(h) < 20) {
            return null;
        }
        List<String> who = new ArrayList<>();
        for (int p = 0; p < us.players.size(); p++) {
            PlayerStats player = us.players.get(p);
            int pos = us.squad.position(player);
            if (free[p] >= 0.3 && meta.posShare(h, pos) >= 0.25 && plays(us, player, h)) {
                who.add(player.nick());
            }
        }
        return who.size() >= 2 ? "флекс: " + String.join(" / ", who) : null;
    }

    /** Наш игрок реально играет героя: есть 3+ игры, или брал его в турнире, или играл за 60 дней. */
    boolean plays(SquadModel model, PlayerStats p, int h) {
        PlayerStats.HeroLine line = p.heroes.get(h);
        return (line != null && line.games >= 3) || model.tourneyPicks(p, h) > 0
                || model.recent.get(p).getOrDefault(h, NO_RECENT)[0] > 0;
    }

    /** Позиция → их герой, если его игрок известен (назначен вручную или вероятность ≥ 35%). */
    Map<Integer, Integer> knownPositions(SquadModel them, List<Assignment> theirs) {
        Map<Integer, Integer> result = new HashMap<>();
        for (Assignment a : theirs) {
            if (!a.fixed() && a.probability() < 0.35) {
                continue;
            }
            them.players.stream().filter(p -> p.nick().equals(a.player())).findFirst()
                    .ifPresent(p -> result.put(them.squad.position(p), a.heroId()));
        }
        return result;
    }

    static List<Integer> laneEnemies(int ourPos, Map<Integer, Integer> theirLaners) {
        return LaneStats.opposingPositions(ourPos).stream().filter(theirLaners::containsKey).map(theirLaners::get).toList();
    }

    LaneStats.Outcome laneVs(int h, int pos, Map<Integer, Integer> theirLaners) {
        List<Integer> enemies = laneEnemies(pos, theirLaners);
        return enemies.isEmpty() ? null : ctx.lanes.lane(h, pos, enemies);
    }

    /** «линия ▲ vs Lina: 62% / 14%» — доля выигранных и проигранных линий, если результат заметно отличается от ровного. */
    private String laneNote(int h, int pos, Map<Integer, Integer> theirLaners) {
        LaneStats.Outcome o = laneVs(h, pos, theirLaners);
        if (o == null || Math.abs(o.advantage()) < 0.1) {
            return null;
        }
        String enemies = String.join(" + ", laneEnemies(pos, theirLaners).stream().map(meta::name).toList());
        return (o.advantage() > 0 ? "линия ▲ vs " : "линия ▼ vs ") + enemies + ": " + Md.pct(o.win()) + " / " + Md.pct(o.loss());
    }

    /**
     * Для каждого нашего игрока без героя, у кого на линии уже известны их герои: 3 его героя, лучшие против них
     * (линия + по игре), с числом его игр.
     */
    private List<LaneOption> laneOptions(SquadModel us, double[][] prob, Set<Integer> used, Map<Integer, Integer> theirLaners) {
        if (ctx.lanes.isEmpty() || theirLaners.isEmpty()) {
            return List.of();
        }
        double[] free = freeProbability(prob, us.players.size());
        List<LaneOption> result = new ArrayList<>();
        for (int p = 0; p < us.players.size(); p++) {
            PlayerStats player = us.players.get(p);
            int pos = us.squad.position(player);
            List<Integer> enemies = laneEnemies(pos, theirLaners);
            if (free[p] < 0.5 || enemies.isEmpty()) {
                continue;
            }
            List<Reasoned> heroes = new ArrayList<>();
            for (Counters.Pool c : Counters.pool(ctx, player, pos, enemies, used, 3)) {
                List<String> reasons = new ArrayList<>();
                reasons.add(c.games() + " игр" + (Double.isNaN(c.winrate()) ? "" : ", " + Md.pct(c.winrate()))
                        + (c.recent60() > 0 ? ", " + c.recent60() + " за 2 мес." : ""));
                if (c.hero().laneMatches() > 0) {
                    reasons.add("линия " + Md.pct(c.hero().laneWin()) + " / " + Md.pct(c.hero().laneLoss()));
                }
                if (!Double.isNaN(c.hero().gameAdv())) {
                    reasons.add("по игре " + Md.signedPct(c.hero().gameAdv()));
                }
                heroes.add(new Reasoned(c.hero().heroId(), c.hero().name(), c.score(), player.nick(), reasons));
            }
            if (!heroes.isEmpty()) {
                result.add(new LaneOption(player.nick(), pos, enemies.stream().map(meta::name).toList(), heroes));
            }
        }
        return result;
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
            double damage = banDamage(them, h, ourPicks, vs, avg);
            List<String> reasons = new ArrayList<>();
            reasons.add("возьмут: " + Md.pct(e.getValue()) + " (" + them.players.get(theirs.bestPlayer().get(h)).nick() + ")");
            reasons.addAll(banReasons(them, h, ourPicks, vs, avg));
            result.add(new Reasoned(h, meta.name(h), e.getValue() * damage, null, reasons));
        }
        result.sort(Comparator.comparingDouble(Reasoned::score).reversed());
        return result.stream().limit(8).toList();
    }

    /** Вред для нас, если они возьмут h: угроза игрока на герое, контрпик наших пиков, наша плохая история против него. */
    private double banDamage(SquadModel them, int h, List<Integer> ourPicks, Map<Integer, int[]> vs, double avg) {
        double threat = them.threat.getOrDefault(h, 0.0);
        double counter = Math.max(0, avgCounter(h, ourPicks));
        return 0.4 + threat + 6 * counter + 0.3 * nemesis(h, vs, avg);
    }

    private static double nemesis(int h, Map<Integer, int[]> vs, double avg) {
        int[] hist = vs.get(h);
        return hist == null || hist[0] < 40 ? 0 : Scoring.clamp01((avg - Scoring.shrink(hist[1], hist[0], avg, 30)) / 0.08);
    }

    /** Причины бана кроме «возьмут»: угроза, контрпик наших, нам неудобен. */
    private List<String> banReasons(SquadModel them, int h, List<Integer> ourPicks, Map<Integer, int[]> vs, double avg) {
        List<String> reasons = new ArrayList<>();
        double threat = them.threat.getOrDefault(h, 0.0);
        int[] hist = vs.get(h);
        // угроза — та же, что на странице соперника (UX-04): показываем всегда, чтобы цифры сходились
        if (threat > 0) {
            reasons.add("угроза " + Md.dec(threat));
        }
        String cnt = bestPair(h, ourPicks, false);
        if (cnt != null) {
            reasons.add("контрит наш " + cnt);
        }
        if (nemesis(h, vs, avg) > 0.3) {
            reasons.add("нам неудобен: " + Md.pct(Scoring.shrink(hist[1], hist[0], avg, 30)));
        }
        return reasons;
    }

    /**
     * Баны по позициям (по запросу пользователя 1.11): для каждого игрока соперника — до 10 героев с наибольшим «доля героя
     * в его распределении × вред для нас». Распределение игрока не зависит от того, выбрал ли он уже героя, поэтому
     * строка остаётся после его пика: на случай свапа.
     */
    private List<PositionBans> positionBans(SquadModel them, SquadModel us, List<Integer> ourPicks, Set<Integer> used,
                                            Distribution theirs, List<Assignment> theirAssignment) {
        Map<Integer, int[]> vs = us.squad.vsEnemies();
        double avg = Squad.average(vs);
        List<PositionBans> result = new ArrayList<>();
        for (int p = 0; p < them.players.size(); p++) {
            PlayerStats player = them.players.get(p);
            final int idx = p;
            List<Reasoned> heroes = new ArrayList<>();
            for (var e : theirs.perPlayer().entrySet()) {
                int h = e.getKey();
                double share = e.getValue()[idx];
                if (used.contains(h) || share < 0.01) {
                    continue;
                }
                List<String> reasons = new ArrayList<>();
                reasons.add("его выбор " + Md.pct(share)); // доля среди его ещё доступных героев
                reasons.addAll(banReasons(them, h, ourPicks, vs, avg));
                heroes.add(new Reasoned(h, meta.name(h), share * banDamage(them, h, ourPicks, vs, avg), player.nick(), reasons));
            }
            heroes.sort(Comparator.comparingDouble(Reasoned::score).reversed());
            Assignment picked = theirAssignment.stream().filter(a -> a.player().equals(player.nick()) && (a.fixed() || a.probability() >= 0.5))
                    .findFirst().orElse(null);
            result.add(new PositionBans(them.squad.position(player), player.nick(), picked == null ? null : picked.heroId(),
                    picked == null ? null : picked.name(), heroes.stream().limit(POSITION_BANS).toList(), 1.0));
        }
        return result;
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
