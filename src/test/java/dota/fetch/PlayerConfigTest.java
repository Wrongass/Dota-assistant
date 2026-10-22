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
                    team_name: UPPO TEAM
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
    void manualMapsMustNameGroupTeams() throws Exception {
        String head = """
                our_team: {name: Team Visual}
                league:
                  id: 1
                  group:
                    - {name: SD Team, team_id: 2}
                  manual_maps:
                """;
        String players = """
                players:
                  - account_id: 1
                    nick: x
                    team: our
                """;
        PlayerConfig ok = PlayerConfig.load(write(head + "    - {date: 2026-10-17, winner: SD Team, loser: Team Visual}\n" + players));
        assertEquals("SD Team", ok.league().manualMapsOrEmpty().get(0).winner());

        Path typo = write(head + "    - {date: 2026-10-17, winner: GGsel, loser: Team Visual}\n" + players);
        assertThrows(IllegalArgumentException.class, () -> PlayerConfig.load(typo));
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
                  - {account_id: 1, nick: b, team: opp, team_name: X}
                """);
        assertThrows(IllegalArgumentException.class, () -> PlayerConfig.load(file));
    }

    @Test
    void oppPlayerNeedsTeamName() throws Exception {
        Path file = write("""
                players:
                  - {account_id: 7, nick: e, team: opp}
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
