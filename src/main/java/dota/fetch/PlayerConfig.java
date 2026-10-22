package dota.fetch;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Игроки из config/players.yaml. */
public record PlayerConfig(@JsonProperty("our_team") TeamInfo ourTeam, League league, List<Player> players) {

    /** Команда: название, организация и id команды в OpenDota (team_id из матчей лиги). */
    public record TeamInfo(String name, String organization, @JsonProperty("team_id") Long teamId) {
    }

    /**
     * Турнир: id лиги OpenDota, с какой даты считать матчи текущего сезона (yyyy-MM-dd),
     * команды нашей группы (соперники), имя следующего соперника (для главной страницы сайта) и карты,
     * которых нет в OpenDota, но результат известен с сайта турнира.
     */
    public record League(Long id, String name, String since, List<TeamInfo> group,
                         @JsonProperty("next_opponent") String nextOpponent,
                         @JsonProperty("manual_maps") List<ManualMap> manualMaps) {

        public List<TeamInfo> groupOrEmpty() {
            return group == null ? List.of() : group;
        }

        public List<ManualMap> manualMapsOrEmpty() {
            return manualMaps == null ? List.of() : manualMaps;
        }
    }

    /** Карта, сыгранная, но не попавшая в лигу OpenDota: учитывается только в счёте группы (драфта нет). */
    public record ManualMap(String date, String winner, String loser, String note) {
    }

    public enum Team {
        @JsonProperty("our") OUR,
        @JsonProperty("opp") OPP;

        public String dir() {
            return name().toLowerCase();
        }
    }

    public record Player(
            @JsonProperty("account_id") long accountId,
            String nick,
            Team team,
            Integer position,
            String note,
            @JsonProperty("team_name") String teamName) {
    }

    public static PlayerConfig load(Path path) throws IOException {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory())
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
        PlayerConfig config = yaml.readValue(path.toFile(), PlayerConfig.class);
        config.validate();
        return config;
    }

    private void validate() {
        if (players == null || players.isEmpty()) {
            throw new IllegalArgumentException("players: list is empty");
        }
        Set<Long> seen = new HashSet<>();
        for (Player p : players) {
            if (p.accountId() <= 0) {
                throw new IllegalArgumentException("account_id must be > 0: " + p);
            }
            if (p.team() == null) {
                throw new IllegalArgumentException("team must be our or opp: " + p);
            }
            if (p.position() != null && (p.position() < 1 || p.position() > 5)) {
                throw new IllegalArgumentException("position must be 1..5 or empty: " + p);
            }
            if (p.team() == Team.OPP && (p.teamName() == null || p.teamName().isBlank())) {
                throw new IllegalArgumentException("team_name is required for opp players: " + p);
            }
            if (!seen.add(p.accountId())) {
                throw new IllegalArgumentException("duplicate account_id: " + p.accountId());
            }
        }
        if (league != null) {
            Set<String> teams = new HashSet<>();
            if (ourTeam != null) {
                teams.add(ourTeam.name());
            }
            league.groupOrEmpty().forEach(t -> teams.add(t.name()));
            for (ManualMap m : league.manualMapsOrEmpty()) {
                if (m.date() == null || !teams.contains(m.winner()) || !teams.contains(m.loser())) {
                    throw new IllegalArgumentException(
                            "manual_maps: date, winner and loser (team names from the group) are required: " + m);
                }
            }
        }
    }
}
