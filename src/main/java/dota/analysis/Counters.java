package dota.analysis;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Контрпики к одному или двум героям соперника для нашей позиции: кто лучше стоит против них на линии, кто сильнее
 * по игре и что из этого есть у нашего игрока на этой позиции (сколько игр, винрейт, сколько за 2 месяца).
 * Линия — {@link LaneStats} (STRATZ), по игре — STRATZ, а без него — контрпики OpenDota из {@link Meta}.
 */
public final class Counters {

    /** Герой-кандидат. laneMatches = 0 — по линии данных нет; gameAdv NaN — по игре данных нет. */
    public record Hero(int heroId, String name, String positions, int laneMatches, double laneWin, double laneDraw,
                       double laneLoss, double laneAdv, double gameWin, double gameAdv) {
    }

    /** Герой из пула нашего игрока: игры всего (без Турбо), винрейт, игры за 60 дней. */
    public record Pool(Hero hero, int games, double winrate, int recent60, double score) {
    }

    public record View(List<Integer> enemies, List<String> enemyNames, int position, String player, long accountId,
                       List<Hero> lane, List<Hero> game, List<Pool> ours, boolean hasLane, String sample) {
    }

    /** Кандидат на позицию: доля героя на этой позиции среди его линий (STRATZ) или в разобранных матчах патча. */
    static final double MIN_POS_SHARE = 0.10;
    /** В пул игрока берём героев, на которых у него столько игр, или хотя бы 2 игры за 60 дней. */
    static final int MIN_POOL_GAMES = 5;
    /** Вес преимущества по игре в оценке героя из пула (на линии ±0,3 — обычный разброс, по игре ±0,05). */
    static final double GAME_WEIGHT = 3;

    private Counters() {
    }

    /** Наша позиция по умолчанию против героя: его мид — наш мид, его 1/5 — наша 3, его 3/4 — наша 1. */
    static int defaultPosition(Meta meta, int enemy) {
        return switch (meta.mainPos(enemy)) {
            case 2 -> 2;
            case 1, 5 -> 3;
            case 3, 4 -> 1;
            default -> 2;
        };
    }

    public static View build(AnalysisContext ctx, List<Integer> enemies, int position) {
        Meta meta = ctx.meta;
        LaneStats lanes = ctx.lanes;
        int pos = position >= 1 && position <= 5 ? position
                : enemies.isEmpty() ? 2 : defaultPosition(meta, enemies.get(0));
        List<Hero> candidates = new ArrayList<>();
        if (!enemies.isEmpty()) {
            for (int h : meta.heroes.keySet()) {
                if (enemies.contains(h) || !fitsPosition(meta, lanes, h, pos)) {
                    continue;
                }
                candidates.add(hero(meta, lanes, h, pos, enemies));
            }
        }
        List<Hero> lane = candidates.stream().filter(c -> c.laneMatches() > 0)
                .sorted(Comparator.comparingDouble(Hero::laneAdv).reversed()).limit(12).toList();
        List<Hero> game = candidates.stream().filter(c -> !Double.isNaN(c.gameAdv()))
                .sorted(Comparator.comparingDouble(Hero::gameAdv).reversed()).limit(12).toList();

        PlayerStats player = ctx.ours.byPosition().stream().filter(p -> ctx.ours.position(p) == pos).findFirst().orElse(null);
        List<Pool> ours = player == null || enemies.isEmpty() ? List.of()
                : pool(ctx, player, pos, enemies, Set.copyOf(enemies), 12);
        return new View(enemies, enemies.stream().map(meta::name).toList(), pos,
                player == null ? null : player.nick(), player == null ? 0 : player.config.accountId(),
                lane, game, ours, !lanes.isEmpty(), lanes.isEmpty() ? null : lanes.sample);
    }

    static boolean fitsPosition(Meta meta, LaneStats lanes, int h, int pos) {
        if (!lanes.isEmpty()) {
            int all = 0;
            for (int p = 1; p <= 5; p++) {
                all += lanes.lanesAt(h, p);
            }
            if (all > 0) {
                return (double) lanes.lanesAt(h, pos) / all >= MIN_POS_SHARE;
            }
        }
        return meta.posTotal(h) >= 20 && meta.posShare(h, pos) >= MIN_POS_SHARE;
    }

    static Hero hero(Meta meta, LaneStats lanes, int h, int pos, Collection<Integer> enemies) {
        LaneStats.Outcome o = lanes.lane(h, pos, enemies);
        double game = lanes.gameAdvantage(h, enemies);
        if (Double.isNaN(game) && lanes.isEmpty()) {
            game = openDotaAdvantage(meta, h, enemies);
        }
        return o == null
                ? new Hero(h, meta.name(h), meta.positions(h), 0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, game)
                : new Hero(h, meta.name(h), meta.positions(h), o.matches(), o.win(), o.draw(), o.loss(), o.advantage(),
                o.gameWin(), game);
    }

    private static double openDotaAdvantage(Meta meta, int h, Collection<Integer> enemies) {
        double sum = 0;
        int n = 0;
        for (int e : enemies) {
            double adv = meta.counterAdvantage(h, e, Squad.MIN_PAIR_GAMES);
            if (!Double.isNaN(adv)) {
                sum += adv;
                n++;
            }
        }
        return n == 0 ? Double.NaN : sum / n;
    }

    /**
     * Герои игрока против enemies на позиции pos: только те, на которых он играет (≥ {@link #MIN_POOL_GAMES} игр или
     * ≥ 2 за 60 дней) и только на позиции pos. Оценка — преимущество на линии + {@link #GAME_WEIGHT} × по игре + немного за опыт.
     */
    static List<Pool> pool(AnalysisContext ctx, PlayerStats p, int pos, Collection<Integer> enemies, Set<Integer> exclude,
                           int limit) {
        long now = ctx.date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        var recent = p.form(now - 60 * 86400L, now);
        List<Pool> result = new ArrayList<>();
        for (PlayerStats.HeroLine line : p.heroes.values()) {
            int h = line.heroId;
            var f = recent.get(h);
            int recent60 = f == null ? 0 : f.games;
            // герой должен ходить на эту позицию: иначе «линия» — пара случайных игр (Bane на миде)
            if (exclude.contains(h) || (line.games < MIN_POOL_GAMES && recent60 < 2)
                    || !fitsPosition(ctx.meta, ctx.lanes, h, pos)) {
                continue;
            }
            Hero hero = hero(ctx.meta, ctx.lanes, h, pos, enemies);
            if (hero.laneMatches() == 0 && Double.isNaN(hero.gameAdv())) {
                continue;
            }
            double score = (hero.laneMatches() == 0 ? 0 : hero.laneAdv())
                    + GAME_WEIGHT * (Double.isNaN(hero.gameAdv()) ? 0 : hero.gameAdv())
                    + 0.1 * Math.min(1, line.games / 30.0) + 0.05 * Math.min(1, recent60 / 5.0);
            result.add(new Pool(hero, line.games, line.winrate(), recent60, score));
        }
        result.sort(Comparator.comparingDouble(Pool::score).reversed());
        return result.stream().limit(limit).toList();
    }
}
