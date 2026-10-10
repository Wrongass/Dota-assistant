package dota.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dota.fetch.PlayerConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Статистика игрока по героям из data/raw/{team}/{id}. Турбо и матчи без результата исключены. */
final class PlayerStats {

    private static final ObjectMapper JSON = new ObjectMapper();
    static final int TURBO = 23;
    static final int CAPTAINS_MODE = 2;

    /** Строка «игрок × герой». */
    static final class HeroLine {
        final int heroId;
        int games, wins;
        int yearGames, yearWins;
        int patchGames, patchWins;
        double wGames, wWins;
        double kdaSum;
        long lastPlayed;
        Double percentRank;
        double skill;

        HeroLine(int heroId) {
            this.heroId = heroId;
        }

        double winrate() {
            return games == 0 ? Double.NaN : (double) wins / games;
        }

        double kda() {
            return games == 0 ? 0 : kdaSum / games;
        }
    }

    /** Герой во вражеской команде в матче игрока (для «неудобных» героев). */
    record Enemy(long matchId, int heroId, boolean win) {
    }

    final PlayerConfig.Player config;
    final String personaName;
    final int rankTier;
    final double computedMmr;
    final Map<Integer, HeroLine> heroes = new HashMap<>();
    /** Вражеские герои за последние 2 года. Матчи, сыгранные вместе, дедуплицируются в Analyze по matchId. */
    final List<Enemy> enemies = new ArrayList<>();
    int totalGames, yearGames, patchGames, patchWins, cmGames, cmWins;
    /** Сколько матчей сыграно в одной команде с каждым из сокомандников (account_id → games). */
    final Map<Long, Integer> withTeammate = new HashMap<>();
    final Map<Long, Integer> winsWithTeammate = new HashMap<>();
    /** Игры по линии в разобранных матчах за год: 1 safe, 2 mid, 3 off, 4 jungle. */
    final int[] yearLaneRole = new int[5];

    private PlayerStats(PlayerConfig.Player config, String personaName, int rankTier, double computedMmr) {
        this.config = config;
        this.personaName = personaName;
        this.rankTier = rankTier;
        this.computedMmr = computedMmr;
    }

    static PlayerStats load(Path dataDir, PlayerConfig.Player p, Set<Long> teammates, Instant patchStart,
                            Instant now) throws IOException {
        Path dir = dataDir.resolve("raw").resolve(p.team().dir()).resolve(String.valueOf(p.accountId()));
        JsonNode profile = JSON.readTree(dir.resolve("profile.json").toFile());
        PlayerStats s = new PlayerStats(p, profile.path("profile").path("personaname").asText(p.nick()),
                profile.path("rank_tier").asInt(0), profile.path("computed_mmr").asDouble(Double.NaN));

        long yearAgo = now.getEpochSecond() - 365L * 86400;
        long patch = patchStart.getEpochSecond();

        for (JsonNode m : JSON.readTree(dir.resolve("matches.json").toFile())) {
            if (m.path("radiant_win").isNull() || m.path("game_mode").asInt() == TURBO) {
                continue;
            }
            boolean radiant = m.get("player_slot").asInt() < 128;
            boolean win = radiant == m.get("radiant_win").asBoolean();
            long start = m.get("start_time").asLong();
            int hero = m.get("hero_id").asInt();
            double weight = Scoring.ageWeight((now.getEpochSecond() - start) / 86400.0);

            HeroLine h = s.heroes.computeIfAbsent(hero, HeroLine::new);
            h.games++;
            h.wins += win ? 1 : 0;
            h.wGames += weight;
            h.wWins += win ? weight : 0;
            h.kdaSum += m.path("kda").asDouble();
            h.lastPlayed = Math.max(h.lastPlayed, start);
            s.totalGames++;
            if (start >= yearAgo) {
                h.yearGames++;
                h.yearWins += win ? 1 : 0;
                s.yearGames++;
                int lane = m.path("lane_role").asInt(0);
                if (lane >= 1 && lane <= 4) {
                    s.yearLaneRole[lane]++;
                }
            }
            if (start >= patch) {
                h.patchGames++;
                h.patchWins += win ? 1 : 0;
                s.patchGames++;
                s.patchWins += win ? 1 : 0;
            }
            if (m.get("game_mode").asInt() == CAPTAINS_MODE) {
                s.cmGames++;
                s.cmWins += win ? 1 : 0;
            }

            // heroes: {"0": {account_id, hero_id}, ..., "128": {...}} — ключи player_slot
            JsonNode all = m.path("heroes");
            for (var it = all.fields(); it.hasNext(); ) {
                var e = it.next();
                boolean slotRadiant = Integer.parseInt(e.getKey()) < 128;
                long acc = e.getValue().path("account_id").asLong(0);
                int otherHero = e.getValue().path("hero_id").asInt(0);
                if (slotRadiant != radiant && otherHero > 0 && start >= now.getEpochSecond() - 2 * 365L * 86400) {
                    s.enemies.add(new Enemy(m.get("match_id").asLong(), otherHero, win));
                }
                if (slotRadiant == radiant && acc != p.accountId() && teammates.contains(acc)) {
                    s.withTeammate.merge(acc, 1, Integer::sum);
                    s.winsWithTeammate.merge(acc, win ? 1 : 0, Integer::sum);
                }
            }
        }

        for (JsonNode r : JSON.readTree(dir.resolve("rankings.json").toFile())) {
            HeroLine h = s.heroes.get(r.get("hero_id").asInt());
            if (h != null) {
                h.percentRank = r.get("percent_rank").asDouble();
            }
        }
        for (HeroLine h : s.heroes.values()) {
            h.skill = Scoring.skill(h.percentRank, h.wWins, h.wGames);
        }
        return s;
    }

    /** Топ героев по умению: минимум 5 игр всего, хоть сколько-то свежих (вес ≥ 1). */
    List<HeroLine> bestHeroes(int limit) {
        return heroes.values().stream()
                .filter(h -> h.games >= 5 && h.wGames >= 1.0)
                .sorted(Comparator.comparingDouble((HeroLine h) -> h.skill).reversed())
                .limit(limit)
                .toList();
    }

    double skillOn(int heroId) {
        HeroLine h = heroes.get(heroId);
        return h == null || h.games < 3 ? 0 : h.skill;
    }

    String nick() {
        return config.nick();
    }

    int bracket() {
        return Math.max(1, Math.min(8, rankTier / 10));
    }

    /** Доля игр за год на героях, чья основная позиция в патче = pos. */
    double yearShareOnPos(Meta meta, int pos) {
        double on = 0, all = 0;
        for (HeroLine h : heroes.values()) {
            if (h.yearGames == 0 || meta.posTotal(h.heroId) < 20) {
                continue;
            }
            on += h.yearGames * meta.posShare(h.heroId, pos);
            all += h.yearGames;
        }
        return all == 0 ? 0 : on / all;
    }
}
