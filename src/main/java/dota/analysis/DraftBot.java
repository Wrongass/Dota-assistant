package dota.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Соперник в симуляции драфта (docs/specs/draft-simulation.md): баны и пики за команду соперника.
 * <ul>
 *   <li>Пикает только героев, на которых его свободный игрок реально играет, на позиции этого игрока. Ценность —
 *   прирост лучшей расстановки (комфорт игрока + линия против наших вероятных лейнеров), синергия со своими,
 *   преимущество против наших пиков и поздняя игра.</li>
 *   <li>Срочность: шанс, что герой уйдёт к нам до следующего их пика. Спорного героя бот крадёт раньше, а героя,
 *   которого у нас никто не играет, оставляет на потом.</li>
 *   <li>Баны: наши вероятные герои, вредные для их драфта; спорный герой, которого они не успевают забрать, — в бан.</li>
 *   <li>Последние два пика: двойной вес контрпика по линии и по игре, прибавка за неудобного героя, на которого у нас
 *   нет ответа ({@link HeroTraits#missingAnswers}).</li>
 *   <li>Разброс: случайно из ходов не слабее 90% лучшего (не больше трёх), зерно задаёт сайт.</li>
 * </ul>
 * Наши позиции бот знает только по статистике: ручных правок он не видит.
 */
public final class DraftBot {

    /** Ход бота: герой, бан или пик, кому (для пика) и почему (для тестов и отладки, на сайте не показывается). */
    public record Move(int step, int heroId, String name, boolean pick, String player, double score, List<String> why) {
    }

    /** Герой бота в его итоговой расстановке и где его ждал прогноз по статистике. */
    public record Placement(int heroId, String name, String player, int position, String expectedPlayer,
                            int expectedPosition) {
        public boolean swapped() {
            return !player.equals(expectedPlayer);
        }
    }

    public record Plan(List<Placement> placements, List<String> swaps) {
    }

    /** Оценённый кандидат (пик или бан). */
    record Candidate(int heroId, double score, String player, List<String> why) {
    }

    /** Вес срочности: пик = ценность × (1 + URGENCY × срочность) + DENY × шанс, что герой наш. */
    static final double URGENCY = 0.8;
    static final double DENY = 0.25;
    /** Штраф за героя, которого игрок на этой позиции не играет: такую расстановку бот не выбирает. */
    private static final double OFF = -5;

    private final DraftEngine engine;
    private final Meta meta;
    private final HeroTraits traits;

    public DraftBot(AnalysisContext ctx) {
        this.engine = new DraftEngine(ctx);
        this.meta = ctx.meta;
        this.traits = ctx.traits;
    }

    /** Состояние драфта с точки зрения бота. */
    private final class View {
        final DraftEngine.SquadModel us, bot;
        final boolean botFirst;
        final int step;
        final List<Integer> ourPicks = new ArrayList<>(), botPicks = new ArrayList<>();
        final Set<Integer> used = new HashSet<>();
        final Map<Integer, Integer> ourLaners;
        final DraftEngine.Distribution ours;

        View(Squad usSquad, Squad botSquad, boolean weFirst, List<Integer> heroes) {
            us = engine.new SquadModel(usSquad, 0);
            bot = engine.new SquadModel(botSquad, 0);
            botFirst = !weFirst;
            step = Math.min(heroes.size(), DraftEngine.SLOTS.size());
            for (int i = 0; i < step; i++) {
                int h = heroes.get(i) == null ? 0 : heroes.get(i);
                if (h <= 0) {
                    continue;
                }
                used.add(h);
                DraftEngine.Slot s = DraftEngine.SLOTS.get(i);
                if (s.pick()) {
                    (s.firstTeam() == botFirst ? botPicks : ourPicks).add(h);
                }
            }
            double[][] prob = new double[ourPicks.size()][us.players.size()];
            List<DraftEngine.Assignment> predicted = engine.assign(us, ourPicks, Map.of(), prob);
            ourLaners = engine.knownPositions(us, predicted);
            double[] free = DraftEngine.freeProbability(prob, us.players.size());
            ours = engine.pickDistribution(us, free, ourPicks, botPicks, used);
        }

        /** Индекс следующего слота команды (firstTeam), начиная с from; SLOTS.size(), если такого нет. */
        int nextSlot(int from, boolean firstTeam, boolean pick) {
            for (int i = from; i < DraftEngine.SLOTS.size(); i++) {
                DraftEngine.Slot s = DraftEngine.SLOTS.get(i);
                if (s.firstTeam() == firstTeam && s.pick() == pick) {
                    return i;
                }
            }
            return DraftEngine.SLOTS.size();
        }

        /** Сколько наших пиков между этим ходом и следующим пиком бота. */
        int ourPicksBeforeBotPick() {
            int botNext = nextSlot(step + 1, botFirst, true);
            int n = 0;
            for (int i = step + 1; i < botNext; i++) {
                DraftEngine.Slot s = DraftEngine.SLOTS.get(i);
                if (s.pick() && s.firstTeam() != botFirst) {
                    n++;
                }
            }
            return n;
        }

        /** Комфорт игрока p бота на герое h на его позиции + линия против наших вероятных лейнеров; NaN — не играет. */
        double fit(int h, int p) {
            PlayerStats player = bot.players.get(p);
            int pos = bot.squad.position(player);
            boolean posOk = meta.posTotal(h) < 20 || meta.posShare(h, pos) >= 0.10 || bot.tourneyPicks(player, h) > 0;
            if (!posOk || !engine.plays(bot, player, h)) {
                return Double.NaN;
            }
            int[] r = bot.squad.bracketRange(player);
            return Scoring.patchFit(bot.squad.metaStrength(h, pos, r[0], r[1]), player.skillOn(h)) / 100
                    + 0.05 * Math.min(2, bot.tourneyPicks(player, h)) + DraftEngine.LANE_WEIGHT * lane(h, pos);
        }

        double lane(int h, int pos) {
            LaneStats.Outcome o = engine.laneVs(h, pos, ourLaners);
            return o == null ? 0 : o.advantage();
        }

        /** Лучшая расстановка героев по игрокам бота: perm[герой] = игрок; total — сумма fit (не играет — OFF). */
        Best best(List<Integer> heroes) {
            int n = bot.players.size();
            double[][] w = new double[heroes.size()][n];
            for (int i = 0; i < heroes.size(); i++) {
                for (int p = 0; p < n; p++) {
                    double f = fit(heroes.get(i), p);
                    w[i][p] = Double.isNaN(f) ? OFF : f;
                }
            }
            int[] players = new int[n];
            for (int p = 0; p < n; p++) {
                players[p] = p;
            }
            Best best = new Best(new int[0], heroes.isEmpty() ? 0 : Double.NEGATIVE_INFINITY, w);
            if (heroes.isEmpty()) {
                return best;
            }
            for (int[] perm : Squad.permutations(players, Math.min(heroes.size(), n))) {
                double total = 0;
                for (int i = 0; i < perm.length; i++) {
                    total += w[i][perm[i]];
                }
                if (total > best.total) {
                    best = new Best(perm, total, w);
                }
            }
            return best;
        }
    }

    record Best(int[] perm, double total, double[][] weight) {
    }

    /** Ход бота за команду botSquad на шаге heroes.size(). weFirst — первый ход у usSquad (у нас). */
    public Move next(Squad usSquad, Squad botSquad, boolean weFirst, List<Integer> heroes, long seed) {
        View v = new View(usSquad, botSquad, weFirst, heroes);
        if (v.step >= DraftEngine.SLOTS.size()) {
            throw new IllegalStateException("драфт закончен");
        }
        DraftEngine.Slot slot = DraftEngine.SLOTS.get(v.step);
        if (slot.firstTeam() != v.botFirst) {
            throw new IllegalStateException("сейчас ход нашей команды");
        }
        List<Candidate> candidates = slot.pick() ? pickCandidates(v) : banCandidates(v);
        if (candidates.isEmpty()) {
            // крайний случай: никто из их свободных игроков не играет ни одного доступного героя — берём лучшего по мете
            int any = meta.heroes.keySet().stream().filter(h -> !v.used.contains(h)).findFirst().orElseThrow();
            candidates = List.of(new Candidate(any, 0, null, List.of("нет своих героев")));
        }
        Candidate c = choose(candidates, random(seed, v.step));
        return new Move(v.step, c.heroId(), meta.name(c.heroId()), slot.pick(), c.player(), c.score(), c.why());
    }

    private List<Candidate> pickCandidates(View v) {
        Best base = v.best(v.botPicks);
        int picksLeft = DraftEngine.left(v.step, v.botFirst, true);
        int ourPicksLeft = DraftEngine.left(v.step, !v.botFirst, true);
        boolean lastPicks = picksLeft <= 2;
        int ourBefore = v.ourPicksBeforeBotPick();
        List<Candidate> result = new ArrayList<>();
        for (int h : meta.heroes.keySet()) {
            if (v.used.contains(h)) {
                continue;
            }
            List<Integer> with = new ArrayList<>(v.botPicks);
            with.add(h);
            Best b = v.best(with);
            int idx = with.size() - 1;
            if (b.perm().length <= idx || b.weight()[idx][b.perm()[idx]] <= OFF) {
                continue; // герой ни одному свободному игроку
            }
            PlayerStats player = v.bot.players.get(b.perm()[idx]);
            int pos = v.bot.squad.position(player);
            double marginal = b.total() - base.total();
            double syn = engine.avgSynergy(h, v.botPicks);
            double cnt = engine.avgCounter(h, v.ourPicks);
            HeroTraits.Traits t = traits.of(h);
            double late = 3 * meta.lateDelta(h) + 0.02 * t.late();
            double fight = t.get("teamfight") > 0 && traits.sum(v.botPicks, "teamfight") >= 2 ? 0.02 : 0;
            List<String> why = new ArrayList<>();
            double value = marginal + 4 * syn + (lastPicks ? 8 : 4) * cnt + late + fight;
            if (lastPicks) {
                value += DraftEngine.LANE_WEIGHT * v.lane(h, pos); // линия на последних пиках вдвойне
                List<String> missing = traits.missingAnswers(h, v.ourPicks);
                if (!missing.isEmpty()) {
                    value += awkwardBonus(missing.size(), ourPicksLeft);
                    why.add("у нас нет ответа: " + String.join(", ", missing));
                }
            }
            double ourNext = v.ours.next().getOrDefault(h, 0.0);
            double urgency = urgency(ourNext, ourBefore);
            double ourChance = v.ours.chance().getOrDefault(h, 0.0);
            double score = pickScore(value, urgency, ourChance);
            if (urgency >= 0.2) {
                why.add("срочно: уйдёт к нам с шансом " + Md.pct(urgency));
            }
            result.add(new Candidate(h, score, player.nick(), why));
        }
        return result;
    }

    /** Прибавка за неудобного героя: больше, если у нас не осталось пиков, чтобы найти ответ. */
    static double awkwardBonus(int missingAnswers, int ourPicksLeft) {
        return 0.12 * missingAnswers * (ourPicksLeft == 0 ? 1.5 : 1);
    }

    /** Шанс, что герой уйдёт к нам за ourPicks наших пиков до их следующего пика. */
    static double urgency(double ourNextPickChance, int ourPicks) {
        return 1 - Math.pow(1 - Math.min(1, ourNextPickChance), ourPicks);
    }

    /** Оценка пика: ценность, усиленная срочностью, плюс отнять героя у нас. */
    static double pickScore(double value, double urgency, double ourChance) {
        return value + URGENCY * urgency * Math.max(0, value) + DENY * ourChance;
    }

    /**
     * Оценка бана: шанс, что мы возьмём героя × (насколько он наш + вред их драфту). Спорный герой, которого бот
     * сам не успевает забрать (наш пик раньше их пика), — ×1,5; спорный, которого они заберут первыми, — ×0,3: его
     * лучше украсть пиком. Плюс привычка банить его в турнире.
     */
    static double banScore(double ourChance, double ourThreat, double harmToThem, boolean botPlaysIt, boolean weGetFirst,
                           double habit) {
        double contested = !botPlaysIt ? 1 : weGetFirst ? 1.5 : 0.3;
        return ourChance * (0.4 + ourThreat + harmToThem) * contested + 0.2 * habit;
    }

    private List<Candidate> banCandidates(View v) {
        int ourNextPick = v.nextSlot(v.step + 1, !v.botFirst, true);
        int botNextPick = v.nextSlot(v.step + 1, v.botFirst, true);
        boolean weGetFirst = ourNextPick < botNextPick;
        List<Candidate> result = new ArrayList<>();
        for (var e : v.ours.chance().entrySet()) {
            int h = e.getKey();
            if (v.used.contains(h) || e.getValue() < 0.01) {
                continue;
            }
            boolean botPlays = playable(v, h);
            double harm = 6 * Math.max(0, engine.avgCounter(h, v.botPicks));
            double score = banScore(e.getValue(), v.us.threat.getOrDefault(h, 0.0), harm, botPlays, weGetFirst,
                    v.bot.banRate(h));
            List<String> why = new ArrayList<>();
            why.add("наш герой: " + Md.pct(e.getValue()));
            if (botPlays) {
                why.add(weGetFirst ? "общий герой, мы заберём раньше" : "общий герой, заберут пиком");
            }
            result.add(new Candidate(h, score, null, why));
        }
        return result;
    }

    private static List<Integer> append(List<Integer> list, int h) {
        List<Integer> r = new ArrayList<>(list);
        r.add(h);
        return r;
    }

    /** Герой встаёт в лучшую расстановку бота (есть свободный игрок, который его играет). */
    private boolean playable(View v, int h) {
        List<Integer> with = append(v.botPicks, h);
        Best b = v.best(with);
        int idx = with.size() - 1;
        return b.perm().length > idx && b.weight()[idx][b.perm()[idx]] > OFF;
    }

    /**
     * Генератор для хода: зерно сайта и номер хода перемешиваются (у java.util.Random первое число почти не меняется
     * на соседних зёрнах, и разброса не было бы).
     */
    static Random random(long seed, int step) {
        return new Random(new java.util.SplittableRandom(seed * 31 + step).nextLong());
    }

    /** Случайно из лучших: оценка не ниже 90% от лучшей, не больше трёх, с весом по оценке. */
    static Candidate choose(List<Candidate> candidates, Random random) {
        List<Candidate> sorted = candidates.stream().sorted(Comparator.comparingDouble(Candidate::score).reversed()).toList();
        Candidate best = sorted.get(0);
        if (best.score() <= 0) {
            return best;
        }
        List<Candidate> top = sorted.stream().filter(c -> c.score() >= 0.9 * best.score()).limit(3).toList();
        double total = top.stream().mapToDouble(Candidate::score).sum();
        double r = random.nextDouble() * total;
        for (Candidate c : top) {
            r -= c.score();
            if (r <= 0) {
                return c;
            }
        }
        return top.get(top.size() - 1);
    }

    /**
     * Итоговая расстановка бота: лучшая по комфорту игроков и линиям против нашей расстановки по статистике. Если
     * она отличается от прогноза по статистике — свап, с текстом для итога.
     */
    public Plan plan(Squad usSquad, Squad botSquad, boolean weFirst, List<Integer> heroes) {
        View v = new View(usSquad, botSquad, weFirst, heroes);
        Best b = v.best(v.botPicks);
        double[][] prob = new double[v.botPicks.size()][v.bot.players.size()];
        List<DraftEngine.Assignment> expected = engine.assign(v.bot, v.botPicks, Map.of(), prob);
        List<Placement> placements = new ArrayList<>();
        List<String> swaps = new ArrayList<>();
        for (int i = 0; i < v.botPicks.size(); i++) {
            int h = v.botPicks.get(i);
            PlayerStats player = v.bot.players.get(b.perm()[i]);
            String expectedNick = expected.get(i).player();
            PlayerStats exp = v.bot.players.stream().filter(p -> p.nick().equals(expectedNick)).findFirst().orElse(player);
            Placement pl = new Placement(h, meta.name(h), player.nick(), v.bot.squad.position(player), exp.nick(),
                    v.bot.squad.position(exp));
            placements.add(pl);
            if (pl.swapped()) {
                swaps.add(pl.name() + ": ждали на позиции " + pl.expectedPosition() + " (" + pl.expectedPlayer()
                        + "), играет позицию " + pl.position() + " (" + pl.player() + ")");
            }
        }
        return new Plan(placements, swaps);
    }
}
