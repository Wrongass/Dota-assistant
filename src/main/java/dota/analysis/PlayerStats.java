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

    /**
     * Недавний матч (за {@link #RECENT_DAYS} дней, Турбо тоже): для срезов «14 дней / 2 месяца / с командой» и списка
     * последних матчей. mates — account_id игроков его команды из конфига, бывших в этом матче на его стороне.
     */
    record Game(long matchId, long start, int heroId, boolean win, int kills, int deaths, int assists, int partySize,
                boolean turbo, int gameMode, List<Long> mates) {
    }

    /** Сколько дней истории хранить в {@link #recent}. */
    static final int RECENT_DAYS = 120;

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
    /** Недавние матчи, новые первыми. */
    final List<Game> recent = new ArrayList<>();

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

        long recentFrom = now.getEpochSecond() - RECENT_DAYS * 86400L;
        for (JsonNode m : JSON.readTree(dir.resolve("matches.json").toFile())) {
            if (m.path("radiant_win").isNull()) {
                continue;
            }
            if (m.get("start_time").asLong() >= recentFrom && m.get("start_time").asLong() <= now.getEpochSecond() + 86400) {
                s.recent.add(recentGame(m, p.accountId(), teammates));
            }
            if (m.path("game_mode").asInt() == TURBO) {
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
        s.recent.sort(Comparator.comparingLong(Game::start).reversed());
        return s;
    }

    private static Game recentGame(JsonNode m, long self, Set<Long> teammates) {
        boolean radiant = m.get("player_slot").asInt() < 128;
        List<Long> mates = new ArrayList<>();
        for (var it = m.path("heroes").fields(); it.hasNext(); ) {
            var e = it.next();
            long acc = e.getValue().path("account_id").asLong(0);
            if ((Integer.parseInt(e.getKey()) < 128) == radiant && acc != self && teammates.contains(acc)) {
                mates.add(acc);
            }
        }
        return new Game(m.get("match_id").asLong(), m.get("start_time").asLong(), m.get("hero_id").asInt(),
                radiant == m.get("radiant_win").asBoolean(), m.path("kills").asInt(), m.path("deaths").asInt(),
                m.path("assists").asInt(), m.path("party_size").asInt(0), m.path("game_mode").asInt() == TURBO,
                m.path("game_mode").asInt(), List.copyOf(mates));
    }

    /** Герой в окне времени: игры и победы без Турбо, Турбо отдельно, игры с сокомандниками из конфига. */
    static final class Form {
        final int heroId;
        int games, wins, turbo, withTeam, kills, deaths, assists;
        long last;
        final Set<Long> mates = new java.util.TreeSet<>();

        Form(int heroId) {
            this.heroId = heroId;
        }
    }

    /** Срез по героям за [from, to) (секунды эпохи). */
    Map<Integer, Form> form(long from, long to) {
        Map<Integer, Form> result = new HashMap<>();
        for (Game g : recent) {
            if (g.start() < from || g.start() >= to) {
                continue;
            }
            Form f = result.computeIfAbsent(g.heroId(), Form::new);
            f.last = Math.max(f.last, g.start());
            if (g.turbo()) {
                f.turbo++;
                continue;
            }
            f.games++;
            f.wins += g.win() ? 1 : 0;
            f.kills += g.kills();
            f.deaths += g.deaths();
            f.assists += g.assists();
            if (!g.mates().isEmpty()) {
                f.withTeam++;
                f.mates.addAll(g.mates());
            }
        }
        return result;
    }

    /**
     * Все герои разом: id → {доля в играх без Турбо за days дней до asOf, доля в играх с сокомандниками}.
     * Для движка драфта; asOf в бэктесте — время проверяемого матча, чтобы не подсматривать в будущее.
     */
    Map<Integer, double[]> recentShares(long asOf, int days) {
        Map<Integer, int[]> count = new HashMap<>();
        int all = 0, team = 0;
        for (Game g : recent) {
            if (g.turbo() || g.start() >= asOf || g.start() < asOf - days * 86400L) {
                continue;
            }
            int[] c = count.computeIfAbsent(g.heroId(), k -> new int[2]);
            all++;
            c[0]++;
            if (!g.mates().isEmpty()) {
                team++;
                c[1]++;
            }
        }
        Map<Integer, double[]> result = new HashMap<>();
        for (var e : count.entrySet()) {
            result.put(e.getKey(), new double[]{all == 0 ? 0 : (double) e.getValue()[0] / all,
                    team == 0 ? 0 : (double) e.getValue()[1] / team});
        }
        return result;
    }

    /**
     * Доля героя в играх без Турбо за days дней до asOf, плюс отдельно — доля в играх с сокомандниками.
     * Для движка драфта: asOf в бэктесте — время проверяемого матча, чтобы не подсматривать в будущее.
     */
    double[] recentShare(int heroId, long asOf, int days) {
        int all = 0, on = 0, team = 0, teamOn = 0;
        for (Game g : recent) {
            if (g.turbo() || g.start() >= asOf || g.start() < asOf - days * 86400L) {
                continue;
            }
            all++;
            on += g.heroId() == heroId ? 1 : 0;
            if (!g.mates().isEmpty()) {
                team++;
                teamOn += g.heroId() == heroId ? 1 : 0;
            }
        }
        return new double[]{all == 0 ? 0 : (double) on / all, team == 0 ? 0 : (double) teamOn / team};
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
