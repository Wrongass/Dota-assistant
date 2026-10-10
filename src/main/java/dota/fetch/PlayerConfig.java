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
public record PlayerConfig(List<Player> players) {

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
            Integer position) {
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
            if (!seen.add(p.accountId())) {
                throw new IllegalArgumentException("duplicate account_id: " + p.accountId());
            }
        }
    }
}
