package dota.analysis;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RosterTest {

    @Test
    void teammateMatrixIsSymmetricForEveryTeam() throws Exception {
        assumeTrue(Files.isDirectory(Path.of("data/raw")), "нет data/");
        AnalysisContext ctx = AnalysisContext.load(Path.of("config/players.yaml"), Path.of("data"));
        java.util.List<Squad> squads = new java.util.ArrayList<>(ctx.opponents);
        squads.add(ctx.ours);
        for (Squad s : squads) {
            Views.Roster r = s.roster();
            int n = r.together().length;
            for (int a = 0; a < n; a++) {
                for (int b = 0; b < n; b++) {
                    assertEquals(r.together()[a][b], r.together()[b][a], s.teamName() + ": игры пары " + a + "-" + b);
                    assertEquals(r.togetherWins()[a][b], r.togetherWins()[b][a], s.teamName() + ": победы пары " + a + "-" + b);
                }
            }
        }
    }
}
