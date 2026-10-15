package dota.analysis;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Строит Markdown-отчёты в analysis/ из data/: по нашим игрокам и соперникам, по мете патча, по турниру.
 * Расчёт — {@link Squad} → {@link Views}, форматирование — {@link MarkdownRenderer}.
 * Файлы *_plan.md пишутся вручную и генератором не трогаются.
 * <pre>
 *   --config &lt;path&gt;   по умолчанию config/players.yaml
 *   --data &lt;dir&gt;      по умолчанию data
 *   --out &lt;dir&gt;       по умолчанию analysis
 *   --date yyyy-MM-dd  дата отчёта (по умолчанию сегодня, UTC); от неё считается давность матчей
 * </pre>
 */
public class Analyze {

    public static void main(String[] args) throws IOException {
        Path configPath = Path.of("config/players.yaml");
        Path dataDir = Path.of("data");
        Path out = Path.of("analysis");
        LocalDate date = LocalDate.now(ZoneOffset.UTC);
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config" -> configPath = Path.of(args[++i]);
                case "--data" -> dataDir = Path.of(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                case "--date" -> date = LocalDate.parse(args[++i]);
                default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
            }
        }
        AnalysisContext ctx = AnalysisContext.load(configPath, dataDir, date);
        new MarkdownRenderer(ctx).writeAll(out);
        System.out.println("Analysis written to " + out.toAbsolutePath());
    }
}
