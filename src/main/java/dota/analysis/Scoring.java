package dota.analysis;

/** Формулы оценок. Все веса собраны здесь, чтобы их было легко крутить. */
final class Scoring {

    /** Период полураспада веса матча: игра годичной давности весит вдвое меньше сегодняшней. */
    static final double HALF_LIFE_DAYS = 365;

    private Scoring() {
    }

    /** Сглаживание доли к prior с весом k «виртуальных» игр (байесовское среднее). */
    static double shrink(double wins, double games, double prior, double k) {
        return (wins + prior * k) / (games + k);
    }

    static double clamp01(double x) {
        return Math.max(0, Math.min(1, x));
    }

    static double ageWeight(double ageDays) {
        return Math.pow(0.5, Math.max(0, ageDays) / HALF_LIFE_DAYS);
    }

    /**
     * «Насколько хорошо игрок играет на герое», 0..100.
     * <ul>
     *   <li>40% — percent_rank из /players/{id}/rankings: место среди всех игроков этого героя;</li>
     *   <li>35% — винрейт с учётом давности (полураспад год), сглаженный к 50% весом 10 игр: 40% → 0, 60% → 1;</li>
     *   <li>25% — опыт: ln(1 + взвешенные игры) / ln(1 + 40), 40 «свежих» игр = максимум.</li>
     * </ul>
     * Сглаживание не даёт герою с 3/3 победами обогнать героя с 60% на 100 играх.
     */
    static double skill(Double percentRank, double weightedWins, double weightedGames) {
        double pr = percentRank == null ? 0.3 : percentRank;
        double wr = clamp01((shrink(weightedWins, weightedGames, 0.5, 10) - 0.40) / 0.20);
        double exp = clamp01(Math.log1p(weightedGames) / Math.log1p(40));
        return 100 * (0.40 * pr + 0.35 * wr + 0.25 * exp);
    }

    /**
     * Сила героя в патче на позиции, 0..1.
     * <ul>
     *   <li>60% — винрейт в пабах на ранге игрока ±1 корзина: 44% → 0, 56% → 1;</li>
     *   <li>20% — винрейт именно на этой позиции в разобранных матчах патча: 44% → 0, 56% → 1;</li>
     *   <li>20% — доля про-матчей с пиком или баном: 0% → 0, 40%+ → 1 (важно для Captains Mode).</li>
     * </ul>
     */
    static double metaStrength(double bracketWinrate, double posWinrate, double proContest) {
        double wr = Double.isNaN(bracketWinrate) ? 0.5 : bracketWinrate;
        return 0.6 * clamp01((wr - 0.44) / 0.12)
                + 0.2 * clamp01((posWinrate - 0.44) / 0.12)
                + 0.2 * clamp01(proContest / 0.40);
    }

    /** Итог «подходит в этом патче», 0..100: поровну сила в мете и умение игрока на герое. */
    static double patchFit(double metaStrength, double skill) {
        return 100 * (0.5 * metaStrength + 0.5 * skill / 100);
    }
}
