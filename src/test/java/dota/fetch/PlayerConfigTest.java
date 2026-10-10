package dota.fetch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PlayerConfigTest {

    @TempDir
    Path dir;

    private Path write(String yaml) throws Exception {
        Path file = dir.resolve("players.yaml");
        Files.writeString(file, yaml);
        return file;
    }

    @Test
    void loadsPlayerWithEmptyPosition() throws Exception {
        PlayerConfig config = PlayerConfig.load(write("""
                players:
                  - account_id: 138244651
                    nick: Wrongass
                    team: our
                    position:
                  - account_id: 42
                    nick: Enemy
                    team: opp
                    position: 3
                """));

        assertEquals(2, config.players().size());
        PlayerConfig.Player first = config.players().get(0);
        assertEquals(138244651L, first.accountId());
        assertEquals(PlayerConfig.Team.OUR, first.team());
        assertNull(first.position());
        assertEquals("opp", config.players().get(1).team().dir());
        assertEquals(3, config.players().get(1).position());
    }

    @Test
    void rejectsUnknownTeam() throws Exception {
        Path file = write("""
                players:
                  - account_id: 1
                    nick: x
                    team: friends
                """);
        assertThrows(Exception.class, () -> PlayerConfig.load(file));
    }

    @Test
    void rejectsDuplicateAccountId() throws Exception {
        Path file = write("""
                players:
                  - {account_id: 1, nick: a, team: our}
                  - {account_id: 1, nick: b, team: opp}
                """);
        assertThrows(IllegalArgumentException.class, () -> PlayerConfig.load(file));
    }

    @Test
    void loadsProjectConfig() throws Exception {
        PlayerConfig config = PlayerConfig.load(Path.of("config/players.yaml"));
        assertTrue(config.players().stream().anyMatch(p -> p.accountId() == 138244651L));
        assertEquals("Team Visual", config.ourTeam().name());
    }
}
