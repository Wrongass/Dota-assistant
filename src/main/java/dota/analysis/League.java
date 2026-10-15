package dota.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dota.fetch.PlayerConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Матчи лиги из data/raw/league/{id}/matches. Переигровки (никто не сделал ни одного убийства) отброшены. */
final class League {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    record Player(long accountId, String personaName, int heroId, boolean radiant, int laneRole, int gpm,
                  int kills, int deaths, int assists) {
    }

    /** Шаг драфта: order — порядковый номер, radiant — чей ход. */
    record DraftStep(int order, boolean pick, boolean radiant, int heroId) {
    }

    record Match(long matchId, long startTime, int durationSec, boolean radiantWin,
                 long radiantTeamId, String radiantName, long direTeamId, String direName,
                 List<Player> players, List<DraftStep> draft) {

        boolean involves(long teamId) {
            return radiantTeamId == teamId || direTeamId == teamId;
        }

        boolean isRadiant(long teamId) {
            return radiantTeamId == teamId;
        }

        boolean won(long teamId) {
            return isRadiant(teamId) == radiantWin;
        }

        long opponentId(long teamId) {
            return isRadiant(teamId) ? direTeamId : radiantTeamId;
        }

        String opponentName(long teamId) {
            return isRadiant(teamId) ? direName : radiantName;
        }

        List<Player> side(boolean radiant) {
            return players.stream().filter(p -> p.radiant() == radiant).toList();
        }

        String date() {
            return DATE.format(Instant.ofEpochSecond(startTime)) + " UTC";
        }
    }

    final long id;
    final List<Match> matches = new ArrayList<>();
    int remakes;

    private League(long id) {
        this.id = id;
    }

    static League load(Path dataDir, PlayerConfig.League config) throws IOException {
        League league = new League(config.id());
        Path dir = dataDir.resolve("raw/league").resolve(String.valueOf(config.id())).resolve("matches");
        if (!Files.isDirectory(dir)) {
            return league;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                JsonNode m = JSON.readTree(f.toFile());
                Match match = parse(m);
                if (match == null) {
                    league.remakes++;
                } else {
                    league.matches.add(match);
                }
            }
        }
        league.matches.sort(Comparator.comparingLong(Match::startTime));
        return league;
    }

    static Match parse(JsonNode m) {
        List<Player> players = new ArrayList<>();
        int totalKills = 0;
        for (JsonNode p : m.path("players")) {
            players.add(new Player(p.path("account_id").asLong(0), p.path("personaname").asText(""),
                    p.path("hero_id").asInt(), p.path("player_slot").asInt() < 128,
                    p.path("lane_role").asInt(0), p.path("gold_per_min").asInt(),
                    p.path("kills").asInt(), p.path("deaths").asInt(), p.path("assists").asInt()));
            totalKills += p.path("kills").asInt();
        }
        if (totalKills == 0) {
            return null; // переигровка: лобби пересоздали, статистика пустая
        }
        List<DraftStep> draft = new ArrayList<>();
        for (JsonNode s : m.path("picks_bans")) {
            draft.add(new DraftStep(s.path("order").asInt(), s.path("is_pick").asBoolean(),
                    s.path("team").asInt() == 0, s.path("hero_id").asInt()));
        }
        draft.sort(Comparator.comparingInt(DraftStep::order));
        return new Match(m.path("match_id").asLong(), m.path("start_time").asLong(), m.path("duration").asInt(),
                m.path("radiant_win").asBoolean(),
                m.path("radiant_team_id").asLong(0), m.path("radiant_name").asText("?"),
                m.path("dire_team_id").asLong(0), m.path("dire_name").asText("?"),
                players, draft);
    }

    List<Match> of(long teamId) {
        return matches.stream().filter(m -> m.involves(teamId)).toList();
    }
}
