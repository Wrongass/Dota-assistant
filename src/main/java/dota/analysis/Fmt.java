package dota.analysis;

/** Форматирование чисел и меток так же, как в Markdown-отчётах, — для шаблонов сайта. */
public final class Fmt {

    private Fmt() {
    }

    public static String pct(double x) {
        return Md.pct(x);
    }

    public static String signedPct(double x) {
        return Md.signedPct(x);
    }

    public static String num(double x) {
        return Md.num(x);
    }

    public static String dec(double x) {
        return Md.dec(x);
    }

    /** "▲ сильный" / "▼ слабый" / "" по винрейту. */
    public static String label(double winrate) {
        return Squad.label(winrate);
    }

    /** CSS-класс метки: strong / weak / "". */
    public static String labelClass(double winrate) {
        if (Double.isNaN(winrate)) {
            return "";
        }
        return winrate >= Meta.STRONG_WR ? "strong" : winrate <= Meta.WEAK_WR ? "weak" : "";
    }

    public static String rank(int rankTier) {
        return Meta.bracketName(rankTier);
    }

    /** Коротко: L1, A5, D3, Imm. */
    public static String rankShort(int rankTier) {
        int b = rankTier / 10, star = rankTier % 10;
        String[] letters = {"", "H", "G", "C", "Ar", "L", "A", "D", "Imm"};
        if (b < 1 || b > 8) {
            return "—";
        }
        return letters[b] + (b < 8 && star > 0 ? star : "");
    }

    public static String topPercent(Double percentRank) {
        return MarkdownRenderer.topPercent(percentRank);
    }

    public static String lane(int laneRole) {
        return MarkdownRenderer.lane(laneRole);
    }

    public static String ratio(int wins, int games) {
        return games == 0 ? "—" : Md.pct((double) wins / games);
    }

    public static String countList(java.util.List<Views.Count> counts, boolean withWins) {
        return MarkdownRenderer.countList(counts, withWins);
    }

    public static String strongThreshold() {
        return Md.num(100 * Meta.STRONG_WR) + "%";
    }

    public static String weakThreshold() {
        return Md.num(100 * Meta.WEAK_WR) + "%";
    }
}
