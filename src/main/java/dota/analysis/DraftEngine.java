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

    /** heroes[i] — герой хода i (0 — ход пропущен). */
    public record State(String opponentSlug, boolean weFirst, List<Integer> heroes) {
    }

    public record Reasoned(int heroId, String name, double score, String player, List<String> reasons) {
    }

    /** Кто на герое: лучший игрок, его вероятность и альтернатива. */
    public record Assignment(int heroId, String name, String player, double probability, String alternative,
                             double alternativeProbability) {
    }

    public record Advice(int step, boolean finished, boolean oursNext, boolean pickNext, List<Integer> ourPicks,
                         List<Integer> theirPicks, List<Integer> ourBans, List<Integer> theirBans,
                         List<Assignment> theirAssignment, List<Assignment> ourAssignment,
                         List<Remaining> theirRemaining, List<Remaining> ourRemaining,
                         List<Reasoned> theirNextPicks, List<Reasoned> banSuggestions,
                         List<Reasoned> pickSuggestions, List<Reasoned> theirLikelyBans, long micros) {
    }

    private final AnalysisContext ctx;
    private final Meta meta;

    public DraftEngine(AnalysisContext ctx) {
        this.ctx = ctx;
        this.meta = ctx.meta;
    }

    // ---------------------------------------------------------------- близость игрок—герой

    /** Всё, что движку нужно знать о команде. Строится один раз на драфт. */
    final class SquadModel {
        final Squad squad;
        final List<PlayerStats> players;
        final Map<Long, Map<Integer, Integer>> tourney;
        final Map<Integer, Integer> bans;
        final Map<Integer, Double> threat = new HashMap<>();

        SquadModel(Squad squad, long excludeMatchId) {
            this.squad = squad;
            this.players = squad.byPosition();
            this.tourney = tourneyPicksOf(squad, excludeMatchId);
            this.bans = tourneyBansOf(squad, excludeMatchId);
            for (Squad.Threat t : squad.threatList(tourney)) {
                threat.put(t.hero(), t.score());
            }
        }

        /** Насколько вероятно, что игрок p возьмёт героя h (относительный вес, > 0). */
        double affinity(PlayerStats p, int h) {
            int pos = squad.position(p);
            double posFit = meta.posTotal(h) >= 20 ? meta.posShare(h, pos) : 0.1;
            double skill = p.skillOn(h) / 100;
            PlayerStats.HeroLine line = p.heroes.get(h);
            // доля героя в играх игрока: поровну за год и за текущий патч — «чем играет в последнее время»
            double yearShare = line == null || p.yearGames == 0 ? 0 : (double) line.yearGames / p.yearGames;
            double patchShare = line == null || p.patchGames == 0 ? 0 : (double) line.patchGames / p.patchGames;
            double share = 0.5 * yearShare + 0.5 * patchShare;
            int t = tourney.getOrDefault(p.config.accountId(), Map.of()).getOrDefault(h, 0);
            return (0.05 + posFit) * (0.15 + skill) * (1 + 1.5 * t) * (1 + 4 * Math.min(share, 0.25));
        }

        int tourneyPicks(PlayerStats p, int h) {
            return tourney.getOrDefault(p.config.accountId(), Map.of()).getOrDefault(h, 0);
        }
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
        int[] all = new int[players];
        for (int i = 0; i < players; i++) {
            all[i] = i;
        }
        double total = 0;
        for (int[] perm : Squad.permutations(all, Math.min(heroes, players))) {
            double w = 1;
            for (int h = 0; h < perm.length; h++) {
                w *= weight[h][perm[h]];
            }
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

    private List<Assignment> assign(SquadModel model, List<Integer> picks, double[][] probOut) {
        double[][] weight = new double[picks.size()][model.players.size()];
        for (int h = 0; h < picks.size(); h++) {
            for (int p = 0; p < model.players.size(); p++) {
                weight[h][p] = model.affinity(model.players.get(p), picks.get(h));
            }
        }
        double[][] prob = assignmentProbabilities(weight);
        for (int h = 0; h < prob.length; h++) {
            System.arraycopy(prob[h], 0, probOut[h], 0, prob[h].length);
        }
        List<Assignment> result = new ArrayList<>();
        for (int h = 0; h < picks.size(); h++) {
            int best = 0, second = -1;
            for (int p = 1; p < model.players.size(); p++) {
                if (prob[h][p] > prob[h][best]) {
                    second = best;
                    best = p;
                } else if (second < 0 || prob[h][p] > prob[h][second]) {
                    second = p;
                }
            }
            result.add(new Assignment(picks.get(h), meta.name(picks.get(h)), model.players.get(best).nick(), prob[h][best],
                    second < 0 ? null : model.players.get(second).nick(), second < 0 ? 0 : prob[h][second]));
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
        return advise(state, 0);
    }

    public Advice advise(State state, long excludeMatchId) {
        Squad oppSquad = ctx.opponent(state.opponentSlug());
        if (oppSquad == null) {
            throw new IllegalArgumentException("нет соперника " + state.opponentSlug());
        }
        return advise(ctx.ours, oppSquad, state.weFirst(), state.heroes(), excludeMatchId);
    }

    /**
     * Совет для команды usSquad против themSquad (для бэктеста — любые две команды группы).
     * excludeMatchId — не учитывать этот матч в турнирной истории (чтобы не подсматривать ответ).
     */
    Advice advise(Squad usSquad, Squad themSquad, boolean weFirst, List<Integer> heroes, long excludeMatchId) {
        long started = System.nanoTime();
        SquadModel us = new SquadModel(usSquad, excludeMatchId);
        SquadModel them = new SquadModel(themSquad, excludeMatchId);
        State state = new State(themSquad.slug(), weFirst, heroes);

        List<Integer> ourPicks = new ArrayList<>(), theirPicks = new ArrayList<>();
        List<Integer> ourBans = new ArrayList<>(), theirBans = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        for (int i = 0; i < heroes.size() && i < SLOTS.size(); i++) {
            int h = heroes.get(i) == null ? 0 : heroes.get(i);
            if (h <= 0) {
                continue;
            }
            Slot slot = SLOTS.get(i);
            boolean ours = slot.firstTeam() == state.weFirst();
            (slot.pick() ? (ours ? ourPicks : theirPicks) : (ours ? ourBans : theirBans)).add(h);
            used.add(h);
        }
        int step = Math.min(heroes.size(), SLOTS.size());
        boolean finished = step >= SLOTS.size();
        Slot next = finished ? null : SLOTS.get(step);
        boolean oursNext = next != null && next.firstTeam() == state.weFirst();

        double[][] theirProb = new double[theirPicks.size()][them.players.size()];
        List<Assignment> theirAssignment = assign(them, theirPicks, theirProb);
        double[][] ourProb = new double[ourPicks.size()][us.players.size()];
        List<Assignment> ourAssignment = assign(us, ourPicks, ourProb);

        Map<Integer, Double> theirNextProb = new LinkedHashMap<>();
        List<Reasoned> theirNext = predictPicks(them, theirProb, theirPicks, ourPicks, used, theirNextProb);
        List<Reasoned> ourPickList = suggestPicks(us, ourProb, ourPicks, theirPicks, used, theirNextProb);
        List<Reasoned> bans = suggestBans(them, us, ourPicks, used, theirNextProb,
                ourPickList.stream().limit(3).map(Reasoned::heroId).toList());
        List<Reasoned> likelyBans = predictTheirBans(us, them, ourProb, ourPicks, theirPicks, used);

        return new Advice(step, finished, oursNext, next != null && next.pick(), ourPicks, theirPicks, ourBans, theirBans,
                theirAssignment, ourAssignment, remaining(them, theirProb), remaining(us, ourProb),
                theirNext.stream().limit(10).toList(), bans, ourPickList, likelyBans,
                (System.nanoTime() - started) / 1000);
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

    /** Вероятность пика героя и самый вероятный игрок, который его возьмёт. */
    record HeroChance(int hero, double probability, PlayerStats player) {
    }

    /**
     * Чей пик и какой герой: у каждого игрока, ещё не выбравшего героя, своё распределение по героям
     * (близость × сила в мете × популярность на его ранге × контекст драфта), нормированное внутри игрока —
     * иначе игрок с большой турнирной историей «перекрикивает» остальных. Итог — смесь этих распределений
     * с весом «вероятность, что игрок ещё свободен». Если остался только мидер — почти всё про героев мидера.
     */
    private List<HeroChance> pickDistribution(SquadModel team, double[] free, List<Integer> allies, List<Integer> enemies,
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
                scores[p] = team.affinity(player, h) * (0.5 + strength) * popularity * context;
                norm[p] += scores[p];
            }
            perPlayer.put(h, scores);
        }
        List<HeroChance> result = new ArrayList<>();
        for (var e : perPlayer.entrySet()) {
            double total = 0, best = -1;
            PlayerStats bestPlayer = null;
            for (int p = 0; p < n; p++) {
                double w = freeTotal == 0 || norm[p] == 0 ? 0 : free[p] / freeTotal * e.getValue()[p] / norm[p];
                total += w;
                if (w > best) {
                    best = w;
                    bestPlayer = team.players.get(p);
                }
            }
            result.add(new HeroChance(e.getKey(), total, bestPlayer));
        }
        result.sort(Comparator.comparingDouble(HeroChance::probability).reversed());
        return result;
    }

    /** Их следующий пик. probOut — вероятности всех героев (для банов и «пик-отказа»). */
    private List<Reasoned> predictPicks(SquadModel them, double[][] prob, List<Integer> theirPicks, List<Integer> ourPicks,
                                        Set<Integer> used, Map<Integer, Double> probOut) {
        double[] free = freeProbability(prob, them.players.size());
        List<Reasoned> result = new ArrayList<>();
        for (HeroChance c : pickDistribution(them, free, theirPicks, ourPicks, used)) {
            probOut.put(c.hero(), c.probability());
            if (result.size() < 15) {
                result.add(new Reasoned(c.hero(), meta.name(c.hero()), c.probability(), c.player().nick(),
                        pickReasons(them, c.player(), c.hero(), theirPicks, ourPicks, "наш")));
            }
        }
        return result;
    }

    /**
     * Что они забанят: наших героев. Берём распределение «что взяли бы наши игроки, ещё не выбравшие героя»
     * (турнир, недавние игры, умение, позиция) — если наш мид уже взял героя, его пул отсюда уходит.
     * Их история банов в турнире — лишь небольшая поправка (×1.3 за каждый бан этого героя).
     */
    private List<Reasoned> predictTheirBans(SquadModel us, SquadModel them, double[][] ourProb, List<Integer> ourPicks,
                                            List<Integer> theirPicks, Set<Integer> used) {
        double[] free = freeProbability(ourProb, us.players.size());
        List<HeroChance> ours = pickDistribution(us, free, ourPicks, theirPicks, used);
        double total = 0;
        Map<HeroChance, Double> weighted = new LinkedHashMap<>();
        for (HeroChance c : ours) {
            double w = c.probability() * Math.pow(1.3, them.bans.getOrDefault(c.hero(), 0));
            weighted.put(c, w);
            total += w;
        }
        final double sum = total;
        return weighted.entrySet().stream()
                .sorted(Map.Entry.<HeroChance, Double>comparingByValue().reversed())
                .limit(8)
                .map(e -> {
                    HeroChance c = e.getKey();
                    List<String> reasons = new ArrayList<>();
                    reasons.add("наш " + c.player().nick());
                    int t = us.tourneyPicks(c.player(), c.hero());
                    if (t > 0) {
                        reasons.add("×" + t + " у нас в турнире");
                    }
                    int banned = them.bans.getOrDefault(c.hero(), 0);
                    if (banned > 0) {
                        reasons.add("банили ×" + banned);
                    }
                    return new Reasoned(c.hero(), meta.name(c.hero()), sum == 0 ? 0 : e.getValue() / sum,
                            c.player().nick(), reasons);
                })
                .toList();
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
                                        Set<Integer> used, Map<Integer, Double> theirNext) {
        double[] free = freeProbability(prob, us.players.size());
        List<Map.Entry<Integer, Double>> theirTop = theirNext.entrySet().stream().limit(8).toList();
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
            double deny = theirNext.getOrDefault(h, 0.0);
            double score = bestFit + 4 * avgSynergy(h, ourPicks) + 4 * avgCounter(h, theirPicks) - 4 * vulnerability
                    + 0.5 * deny;
            List<String> reasons = pickReasons(us, bestPlayer, h, ourPicks, theirPicks, "их");
            if (deny >= 0.05) {
                reasons.add("пик-отказ: у них " + Md.pct(deny));
            }
            result.add(new Reasoned(h, meta.name(h), 100 * score, bestPlayer.nick(), reasons));
        }
        result.sort(Comparator.comparingDouble(Reasoned::score).reversed());
        return result.stream().limit(10).toList();
    }

    /**
     * Баны: вероятность их пика × вред для нас (угроза игрока на герое, контрпик наших пиков, наша плохая история
     * против героя). Герои, которых мы сами хотим взять первыми, не предлагаются — для них «пик-отказ».
     */
    private List<Reasoned> suggestBans(SquadModel them, SquadModel us, List<Integer> ourPicks, Set<Integer> used,
                                       Map<Integer, Double> theirNext, List<Integer> ourWanted) {
        Map<Integer, int[]> vs = us.squad.vsEnemies();
        double avg = Squad.average(vs);
        List<Reasoned> result = new ArrayList<>();
        for (var e : theirNext.entrySet()) {
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
            reasons.add("их пик " + Md.pct(e.getValue()));
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
}
