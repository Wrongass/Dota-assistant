package dota.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Мини-построитель Markdown. */
final class Md {

    private final StringBuilder sb = new StringBuilder();

    Md line(String format, Object... args) {
        sb.append(args.length == 0 ? format : String.format(Locale.ROOT, format, args)).append('\n');
        return this;
    }

    Md blank() {
        sb.append('\n');
        return this;
    }

    Md h1(String text) {
        return line("# " + text).blank();
    }

    Md h2(String text) {
        return blank().line("## " + text).blank();
    }

    Md h3(String text) {
        return blank().line("### " + text).blank();
    }

    Table table(String... headers) {
        return new Table(this, headers);
    }

    @Override
    public String toString() {
        return sb.toString();
    }

    static String pct(double x) {
        return Double.isNaN(x) ? "—" : String.format(Locale.ROOT, "%.1f%%", 100 * x);
    }

    static String signedPct(double x) {
        return Double.isNaN(x) ? "—" : String.format(Locale.ROOT, "%+.1f%%", 100 * x);
    }

    static String num(double x) {
        return Double.isNaN(x) ? "—" : String.format(Locale.ROOT, "%.0f", x);
    }

    static String dec(double x) {
        return Double.isNaN(x) ? "—" : String.format(Locale.ROOT, "%.2f", x);
    }

    static final class Table {
        private final Md md;
        private final String[] headers;
        private final List<String[]> rows = new ArrayList<>();

        private Table(Md md, String[] headers) {
            this.md = md;
            this.headers = headers;
        }

        Table row(Object... cells) {
            String[] r = new String[cells.length];
            for (int i = 0; i < cells.length; i++) {
                r[i] = String.valueOf(cells[i]).replace("|", "\\|");
            }
            rows.add(r);
            return this;
        }

        Md end() {
            if (!md.sb.isEmpty() && !md.sb.toString().endsWith("\n\n")) {
                md.sb.append('\n');
            }
            md.sb.append("| ").append(String.join(" | ", headers)).append(" |\n");
            md.sb.append("|").append("---|".repeat(headers.length)).append('\n');
            for (String[] r : rows) {
                md.sb.append("| ").append(String.join(" | ", r)).append(" |\n");
            }
            if (rows.isEmpty()) {
                md.sb.append("| ").append("— | ".repeat(headers.length).trim()).append('\n');
            }
            return md;
        }
    }
}
