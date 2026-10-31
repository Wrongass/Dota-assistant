package dota.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Новый игрок в конфиге, а его данные ещё не скачаны (на сервере: деплой раньше ежедневной выгрузки). Сайт должен
 * запуститься без этого игрока и показать предупреждение, а не упасть: иначе деплой не проходит проверку /health.
 */
class MissingPlayerDataTest {

    @TempDir
    Path dir;

    @Test
    void playerWithoutDataIsSkippedNotFatal() throws Exception {
        assumeTrue(Files.isDirectory(Path.of("data/raw")), "нет data/");
        Path config = dir.resolve("players.yaml");
        Files.writeString(config, Files.readString(Path.of("config/players.yaml")) + """

                  - account_id: 999000111
                    nick: NoData
                    team: opp
                    team_name: Team Future
                    reserve: true
                """);
        AnalysisContext ctx = AnalysisContext.load(config, Path.of("data"));
        Squad future = ctx.opponent("Team_Future");
        assertNotNull(future);
        assertNull(future.player(999000111L), "игрока без данных нет в команде");
        assertEquals(5, future.players.size(), "основа на месте");
        assertTrue(ctx.missingData().stream().anyMatch(s -> s.contains("NoData")), ctx.missingData().toString());
    }
}
