package dota.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Справочник героев и мета текущего патча из data/raw/constants и data/meta/{patch}. */
final class Meta {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Ранговые корзины OpenDota: 1 Herald … 8 Immortal. */
    static final String[] BRACKET_RU = {"", "Рекрут", "Страж", "Рыцарь", "Герой", "Легенда", "Властелин", "Божество", "Титан"};
    static final String[] BRACKET_EN = {"", "Herald", "Guardian", "Crusader", "Archon", "Legend", "Ancient", "Divine", "Immortal"};

    /** Порог «прямо выделяется» по винрейту на нашем уровне. */
    static final double STRONG_WR = 0.52;
    static final double WEAK_WR = 0.48;

    record Hero(int id, String name, List<String> roles, String attr) {
    }

    /** Пики/победы в пабах по корзинам 1..8 + про-сцена. */
    record HeroStats(long[] picks, long[] wins, long proPick, long proWin, long proBan) {

        double winrate(int fromBracket, int toBracket) {
            long p = 0, w = 0;
            for (int b = fromBracket; b <= toBracket; b++) {
                p += picks[b];
                w += wins[b];
            }
            return p == 0 ? Double.NaN : (double) w / p;
        }

        long picks(int fromBracket, int toBracket) {
            long p = 0;
            for (int b = fromBracket; b <= toBracket; b++) {
                p += picks[b];
            }
            return p;
        }
    }

    record Pair(int a, int b) {
    }

    record PairStats(long games, long wins) {
        double winrate() {
            return (double) wins / games;
        }
    }

    final String patch;
    final Instant patchStart;
    final Map<Integer, Hero> heroes = new HashMap<>();
    final Map<Integer, HeroStats> stats = new HashMap<>();
    /** [hero][pos 1..5] игры и победы в разобранных матчах патча. */
    final Map<Integer, long[]> posGames = new HashMap<>();
    final Map<Integer, long[]> posWins = new HashMap<>();
    /** Базовый винрейт в public-выборке (та же выборка, что для синергий/контрпиков). */
    final Map<Integer, Double> baseWinrate = new HashMap<>();
    /** Пары в одной команде, a < b. */
    final Map<Pair, PairStats> synergy = new HashMap<>();
    /** a против b; wins — победы a. */
    final Map<Pair, PairStats> counters = new HashMap<>();
    long proMatches;
    final long[] bracketMatches = new long[9];

    private Meta(String patch, Instant patchStart) {
        this.patch = patch;
        this.patchStart = patchStart;
    }

    static Meta load(Path dataDir) throws IOException {
        Path constants = dataDir.resolve("raw/constants");
        JsonNode patches = read(constants.resolve("patch.json"));
        JsonNode last = patches.get(patches.size() - 1);
        Meta m = new Meta(last.get("name").asText(), Instant.parse(last.get("date").asText()));
        Path dir = dataDir.resolve("meta").resolve(m.patch);

        read(constants.resolve("heroes.json")).forEach(h -> {
            List<String> roles = new ArrayList<>();
            h.path("roles").forEach(r -> roles.add(r.asText()));
            m.heroes.put(h.get("id").asInt(), new Hero(h.get("id").asInt(), h.get("localized_name").asText(), roles,
                    h.path("primary_attr").asText("")));
        });

        long proPicks = 0;
        for (JsonNode h : read(dir.resolve("hero_stats.json"))) {
            long[] p = new long[9], w = new long[9];
            for (int b = 1; b <= 8; b++) {
                p[b] = h.path(b + "_pick").asLong();
                w[b] = h.path(b + "_win").asLong();
                m.bracketMatches[b] += p[b];
            }
            m.stats.put(h.get("id").asInt(), new HeroStats(p, w,
                    h.path("pro_pick").asLong(), h.path("pro_win").asLong(), h.path("pro_ban").asLong()));
            proPicks += h.path("pro_pick").asLong();
        }
        m.proMatches = proPicks / 10;
        for (int b = 1; b <= 8; b++) {
            m.bracketMatches[b] /= 10;
        }

        for (JsonNode r : rows(dir.resolve("hero_positions.json"))) {
            int hero = r.get("hero_id").asInt(), pos = r.get("pos").asInt();
            m.posGames.computeIfAbsent(hero, k -> new long[6])[pos] += r.get("games").asLong();
            m.posWins.computeIfAbsent(hero, k -> new long[6])[pos] += r.get("wins").asLong();
        }
        for (JsonNode r : rows(dir.resolve("hero_winrate.json"))) {
            m.baseWinrate.put(r.get("hero_id").asInt(), r.get("wins").asDouble() / r.get("games").asDouble());
        }
        for (JsonNode r : rows(dir.resolve("hero_synergy.json"))) {
            m.synergy.put(new Pair(r.get("h1").asInt(), r.get("h2").asInt()),
                    new PairStats(r.get("games").asLong(), r.get("wins").asLong()));
        }
        for (JsonNode r : rows(dir.resolve("hero_counters.json"))) {
            m.counters.put(new Pair(r.get("h1").asInt(), r.get("h2").asInt()),
                    new PairStats(r.get("games").asLong(), r.get("wins").asLong()));
        }
        return m;
    }

    String name(int heroId) {
        Hero h = heroes.get(heroId);
        return h == null ? "#" + heroId : h.name();
    }

    /** Доля игр героя на позиции (1..5) среди разобранных матчей патча; 0, если данных нет. */
    double posShare(int heroId, int pos) {
        long[] g = posGames.get(heroId);
        if (g == null) {
            return 0;
        }
        long total = 0;
        for (int p = 1; p <= 5; p++) {
            total += g[p];
        }
        return total == 0 ? 0 : (double) g[pos] / total;
    }

    long posTotal(int heroId) {
        long[] g = posGames.get(heroId);
        return g == null ? 0 : Stream.of(1, 2, 3, 4, 5).mapToLong(p -> g[p]).sum();
    }

    /** Основная позиция героя или 0, если разобранных игр мало. */
    int mainPos(int heroId) {
        if (posTotal(heroId) < 20) {
            return 0;
        }
        int best = 1;
        for (int p = 2; p <= 5; p++) {
            if (posShare(heroId, p) > posShare(heroId, best)) {
                best = p;
            }
        }
        return best;
    }

    /** Позиции с долей ≥ 20%, например "4/5". */
    String positions(int heroId) {
        if (posTotal(heroId) < 20) {
            return "?";
        }
        List<String> list = new ArrayList<>();
        for (int p = 1; p <= 5; p++) {
            if (posShare(heroId, p) >= 0.20) {
                list.add(String.valueOf(p));
            }
        }
        return list.isEmpty() ? String.valueOf(mainPos(heroId)) : String.join("/", list);
    }

    /** Винрейт героя на позиции в разобранных матчах патча, сглаженный к 50% (вес 30 игр). */
    double posWinrate(int heroId, int pos) {
        long[] g = posGames.get(heroId), w = posWins.get(heroId);
        if (g == null) {
            return 0.5;
        }
        return Scoring.shrink(w[pos], g[pos], 0.5, 30);
    }

    /** Доля про-матчей, где героя взяли или забанили. */
    double proContest(int heroId) {
        HeroStats s = stats.get(heroId);
        return s == null || proMatches == 0 ? 0 : (double) (s.proPick() + s.proBan()) / proMatches;
    }

    double pickRate(int heroId, int from, int to) {
        HeroStats s = stats.get(heroId);
        long matches = 0;
        for (int b = from; b <= to; b++) {
            matches += bracketMatches[b];
        }
        return s == null || matches == 0 ? 0 : (double) s.picks(from, to) / matches;
    }

    double winrate(int heroId, int from, int to) {
        HeroStats s = stats.get(heroId);
        return s == null ? Double.NaN : s.winrate(from, to);
    }

    /** Синергия пары: винрейт пары минус средний базовый винрейт героев. NaN, если игр меньше minGames. */
    double synergyLift(int a, int b, long minGames) {
        PairStats s = synergy.get(new Pair(Math.min(a, b), Math.max(a, b)));
        if (s == null || s.games() < minGames || !baseWinrate.containsKey(a) || !baseWinrate.containsKey(b)) {
            return Double.NaN;
        }
        return s.winrate() - (baseWinrate.get(a) + baseWinrate.get(b)) / 2;
    }

    PairStats synergyStats(int a, int b) {
        return synergy.get(new Pair(Math.min(a, b), Math.max(a, b)));
    }

    /**
     * Преимущество героя a против b: винрейт a против b минус ожидаемый из базовых винрейтов
     * ((wr_a + 1 - wr_b) / 2). Положительное — a контрит b.
     */
    double counterAdvantage(int a, int b, long minGames) {
        PairStats s = counters.get(new Pair(a, b));
        if (s == null || s.games() < minGames || !baseWinrate.containsKey(a) || !baseWinrate.containsKey(b)) {
            return Double.NaN;
        }
        return s.winrate() - (baseWinrate.get(a) + 1 - baseWinrate.get(b)) / 2;
    }

    List<Integer> heroIdsByName() {
        return heroes.values().stream().sorted(Comparator.comparing(Hero::name)).map(Hero::id).toList();
    }

    static String bracketName(int rankTier) {
        int b = rankTier / 10, star = rankTier % 10;
        if (b < 1 || b > 8) {
            return "без ранга";
        }
        return BRACKET_RU[b] + (b < 8 && star > 0 ? " " + star : "") + " (" + BRACKET_EN[b] + ")";
    }

    private static JsonNode read(Path file) throws IOException {
        if (!Files.exists(file)) {
            throw new IOException("нет файла " + file + " — сначала запустите ./gradlew run");
        }
        return JSON.readTree(file.toFile());
    }

    private static JsonNode rows(Path file) throws IOException {
        return read(file).path("rows");
    }
}
