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
     * Турнир: id лиги OpenDota, с какой даты считать матчи текущего сезона (yyyy-MM-dd), текущая стадия (stage, текст),
     * команды нашей группы, соперники плей-ина (не из нашей группы), имя следующего соперника и тех, к кому готовимся
     * дальше (для главной страницы сайта), и карты, которых нет в OpenDota, но результат известен с сайта турнира.
     */
    public record League(Long id, String name, String since, String stage, List<TeamInfo> group, List<TeamInfo> playin,
                         @JsonProperty("next_opponent") String nextOpponent,
                         List<String> prepare,
                         @JsonProperty("manual_maps") List<ManualMap> manualMaps) {

        public List<TeamInfo> groupOrEmpty() {
            return group == null ? List.of() : group;
        }

        public List<TeamInfo> playinOrEmpty() {
            return playin == null ? List.of() : playin;
        }

        /** Все соперники: сначала группа, потом плей-ин. */
        public List<TeamInfo> opponentsOrEmpty() {
            List<TeamInfo> all = new java.util.ArrayList<>(groupOrEmpty());
            all.addAll(playinOrEmpty());
            return all;
        }

        public List<String> prepareOrEmpty() {
            return prepare == null ? List.of() : prepare;
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

    /** reserve — запасной: в состав по умолчанию не входит, его можно поставить в состав на сайте. */
    public record Player(
            @JsonProperty("account_id") long accountId,
            String nick,
            Team team,
            Integer position,
            String note,
            @JsonProperty("team_name") String teamName,
            Boolean reserve) {

        public boolean isReserve() {
            return Boolean.TRUE.equals(reserve);
        }

        /** Тот же игрок на другой позиции и в основе (для состава, выбранного на сайте). */
        public Player at(int newPosition) {
            return new Player(accountId, nick, team, newPosition, note, teamName, false);
        }
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
            league.opponentsOrEmpty().forEach(t -> teams.add(t.name()));
            for (String name : league.nextOpponent() == null ? league.prepareOrEmpty()
                    : java.util.stream.Stream.concat(java.util.stream.Stream.of(league.nextOpponent()),
                    league.prepareOrEmpty().stream()).toList()) {
                if (!teams.contains(name)) {
                    throw new IllegalArgumentException("next_opponent / prepare: no such team in group or playin: " + name);
                }
            }
            for (ManualMap m : league.manualMapsOrEmpty()) {
                if (m.date() == null || !teams.contains(m.winner()) || !teams.contains(m.loser())) {
                    throw new IllegalArgumentException(
                            "manual_maps: date, winner and loser (team names from the group) are required: " + m);
                }
            }
        }
    }
}
