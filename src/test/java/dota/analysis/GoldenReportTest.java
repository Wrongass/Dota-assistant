package dota.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Эталон: закоммиченный analysis/ должен совпадать с тем, что генератор строит из текущих data/ на ту же дату.
 * Ловит регрессии при рефакторинге. Пропускается, если data/ нет (она не в git).
 * После обновления данных эталон обновляется обычным ./gradlew analyze.
 */
class GoldenReportTest {

    private static final Path ANALYSIS = Path.of("analysis");
    private static final Pattern DATE = Pattern.compile("Сгенерировано (\\d{4}-\\d{2}-\\d{2})");

    @TempDir
    Path out;

    @Test
    void generatorReproducesCommittedReports() throws Exception {
        assumeTrue(Files.isDirectory(Path.of("data/raw")) && Files.exists(ANALYSIS.resolve("README.md")),
                "нет data/ или analysis/ — эталон не проверить");
        Matcher m = DATE.matcher(Files.readString(ANALYSIS.resolve("README.md")));
        assertTrue(m.find(), "в analysis/README.md нет даты генерации");

        Analyze.main(new String[]{"--out", out.toString(), "--date", m.group(1)});

        List<Path> expected;
        try (Stream<Path> files = Files.walk(ANALYSIS)) {
            expected = files.filter(Files::isRegularFile)
                    .filter(f -> !f.getFileName().toString().endsWith("_plan.md"))
                    .toList();
        }
        assertFalse(expected.isEmpty());
        for (Path f : expected) {
            Path actual = out.resolve(ANALYSIS.relativize(f).toString());
            assertTrue(Files.exists(actual), "генератор не создал " + f);
            assertEquals(Files.readString(f).replace("\r\n", "\n"), Files.readString(actual).replace("\r\n", "\n"),
                    "отличается " + f);
        }
        try (Stream<Path> files = Files.walk(out)) {
            long generated = files.filter(Files::isRegularFile).count();
            assertEquals(expected.size(), generated, "генератор создал лишние файлы");
        }
    }
}
