package dota.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * С какими предметами герой на позиции чаще выигрывает (STRATZ {@code itemFullPurchase}, только недели текущего патча).
 * <p>
 * Простой винрейт с предметом обманывает: дорогой предмет покупают в уже выигранных играх, а Divine Rapier на 40-й
 * минуте почти всегда «побеждает». Поэтому предмет сравнивается с другими предметами, которые этот герой на этой
 * позиции покупал на той же минуте: ожидаемый винрейт — винрейт остальных покупок той минуты, «прибавка» — насколько
 * предмет выигрывает чаще. Прибавка стягивается к нулю по числу покупок ({@link #PRIOR}), предметы, которые купили
 * меньше {@link #MIN_PURCHASES} раз, не показываются. STRATZ сам не отдаёт строки (минуты) с малым числом игр.
 */
final class HeroItems {

    /** Меньше стольких покупок — предмет не оцениваем. */
    static final long MIN_PURCHASES = 500;
    /** Сглаживание прибавки к нулю: при 1000 покупок остаётся половина. */
    static final double PRIOR = 1000;
    /** На минуте должно быть столько покупок других предметов, чтобы с ними сравнивать. */
    static final long MIN_OTHERS = 100;

    /**
     * Предмет на герое и позиции. lift — сглаженная прибавка к винрейту против покупок той же минуты, rawLift — без
     * сглаживания, z — во сколько раз прибавка больше случайного разброса (|z| ≥ 2 — заметно), minute — средняя
     * минута покупки.
     */
    record Line(int itemId, long purchases, double winrate, double expected, double lift, double rawLift, double z,
                double minute) {
    }

    /** hero → pos → item → минута → {покупок, побед}. */
    private final Map<Integer, Map<Integer, Map<Integer, TreeMap<Integer, long[]>>>> rows = new HashMap<>();

    void add(int hero, int pos, int item, int instance, int minute, long purchases, long wins) {
        if (instance != 0 || purchases <= 0) {
            return; // вторая копия предмета (instance 1) — другая покупка, не смешиваем
        }
        long[] cell = rows.computeIfAbsent(hero, k -> new HashMap<>()).computeIfAbsent(pos, k -> new HashMap<>())
                .computeIfAbsent(item, k -> new TreeMap<>()).computeIfAbsent(minute, k -> new long[2]);
        cell[0] += purchases;
        cell[1] += wins;
    }

    boolean isEmpty() {
        return rows.isEmpty();
    }

    /** Предметы героя на позиции с оценкой; сначала лучшие. Предметы с малым числом покупок не попадают. */
    List<Line> lines(int hero, int pos) {
        Map<Integer, TreeMap<Integer, long[]>> items = rows.getOrDefault(hero, Map.of()).getOrDefault(pos, Map.of());
        // все покупки по минутам — из них вычитаем сам предмет
        Map<Integer, long[]> byMinute = new HashMap<>();
        items.values().forEach(m -> m.forEach((t, c) -> {
            long[] s = byMinute.computeIfAbsent(t, k -> new long[2]);
            s[0] += c[0];
            s[1] += c[1];
        }));
        List<Line> result = new ArrayList<>();
        items.forEach((item, minutes) -> {
            long n = 0, w = 0, used = 0, usedWins = 0;
            double expected = 0, minuteSum = 0;
            for (var e : minutes.entrySet()) {
                long[] c = e.getValue(), all = byMinute.get(e.getKey());
                n += c[0];
                w += c[1];
                minuteSum += (double) e.getKey() * c[0];
                long otherN = all[0] - c[0], otherW = all[1] - c[1];
                if (otherN >= MIN_OTHERS) {
                    used += c[0];
                    usedWins += c[1];
                    expected += c[0] * ((double) otherW / otherN);
                }
            }
            if (n < MIN_PURCHASES) {
                return;
            }
            double raw = Double.NaN, lift = Double.NaN, z = Double.NaN, exp = Double.NaN;
            if (used >= MIN_PURCHASES / 2) {
                exp = expected / used;
                raw = (double) usedWins / used - exp;
                lift = raw * used / (used + PRIOR);
                double p = (double) usedWins / used;
                z = raw / Math.sqrt(Math.max(1e-9, p * (1 - p) / used));
            }
            result.add(new Line(item, n, (double) w / n, exp, lift, raw, z, minuteSum / n));
        });
        result.sort(Comparator.comparingDouble((Line l) -> Double.isNaN(l.lift()) ? -1 : l.lift()).reversed());
        return result;
    }

    /** Позиции, на которых у героя есть оценённые предметы. */
    List<Integer> positions(int hero) {
        return rows.getOrDefault(hero, Map.of()).keySet().stream().sorted().filter(p -> !lines(hero, p).isEmpty()).toList();
    }

    /** Герои, у которых есть строки предметов. */
    java.util.Set<Integer> heroes() {
        return rows.keySet();
    }
}
