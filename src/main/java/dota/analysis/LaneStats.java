package dota.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Линия и матчапы героев из STRATZ ({@code data/raw/stratz/lanes/{неделя}/}, качает {@code dota.fetch.StratzFetcher}).
 * <ul>
 *   <li>Линия: герой на позиции против героя, который стоял с ним на линии, — выигранные, ничейные и проигранные
 *       линии (разгромы входят в выигранные и проигранные) и выигранные игры. Суммируется за все скачанные недели.</li>
 *   <li>По игре: {@code synergy} из heroVsHeroMatchup — преимущество героя над соперником в процентах.</li>
 * </ul>
 * Если данных нет, {@link #isEmpty()} и все оценки — NaN.
 */
final class LaneStats {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ALIAS = Pattern.compile("w(\\d+)p([1-5])");
    /** Сглаживание преимущества на линии к обычному результату героя на позиции: вес 40 линий. */
    static final double PRIOR = 40;
    /** Меньше стольких линий против героя — пару не показываем. */
    static final int MIN_LANES = 30;
    /** Меньше стольких игр против героя — матчап по игре не учитываем. */
    static final int MIN_GAMES = 100;

    /** Сумма линий: матчи, выиграна, ничья, проиграна, выиграна игра. */
    record Cell(int matches, int win, int draw, int loss, int gameWins) {
        static final Cell EMPTY = new Cell(0, 0, 0, 0, 0);

        Cell plus(Cell o) {
            return new Cell(matches + o.matches, win + o.win, draw + o.draw, loss + o.loss, gameWins + o.gameWins);
        }

        double rate(int n) {
            return matches == 0 ? Double.NaN : (double) n / matches;
        }
    }

    /** Итог для героя против одного или нескольких героев линии. advantage — сглаженное (выиграно − проиграно) / линий. */
    record Outcome(int matches, double win, double draw, double loss, double gameWin, double advantage) {
    }

    final String week;
    final String sample;
    /** hero → pos → соперник на линии → Cell. */
    private final Map<Integer, Map<Integer, Map<Integer, Cell>>> lanes = new HashMap<>();
    /** hero → pos → сумма по всем соперникам. */
    private final Map<Integer, Map<Integer, Cell>> totals = new HashMap<>();
    /** hero → соперник → {игр, сумма synergy % × игр} (по неделям складываем с весом игр). */
    private final Map<Integer, Map<Integer, double[]>> game = new HashMap<>();
    /** hero → союзник → {игр, сумма synergy % × игр, побед}. */
    private final Map<Integer, Map<Integer, double[]>> allies = new HashMap<>();
    /** hero → позиция 1..5 → {игр, побед} по stats STRATZ. */
    private final Map<Integer, long[][]> positions = new HashMap<>();
    /** Покупки предметов по минутам. */
    final HeroItems items = new HeroItems();
    /** Выборка только из недель текущего патча (а не последние 4 недели прошлого). */
    boolean patchOnly;
    String patch;
    private static final Pattern MATCHUP = Pattern.compile("mu(\\d*)");
    private static final Pattern ITEMS = Pattern.compile("i(\\d+)p([1-5])");
    private static final Pattern STATS = Pattern.compile("s(\\d+)");

    private LaneStats(String week, String sample) {
        this.week = week;
        this.sample = sample;
    }

    static LaneStats empty() {
        return new LaneStats(null, null);
    }

    static LaneStats load(Path dataDir) throws IOException {
        Path root = dataDir.resolve("raw/stratz/lanes");
        if (!Files.isDirectory(root)) {
            return empty();
        }
        Path dir;
        try (Stream<Path> dirs = Files.list(root)) {
            dir = dirs.filter(Files::isDirectory)
                    .filter(d -> d.getFileName().toString().matches("\\d+"))
                    .max((a, b) -> Long.compare(Long.parseLong(a.getFileName().toString()),
                            Long.parseLong(b.getFileName().toString())))
                    .orElse(null);
        }
        if (dir == null) {
            return empty();
        }
        String sample = "";
        boolean patchOnly = false;
        String patch = null;
        Path info = dir.resolve("_sample.json");
        if (Files.exists(info)) {
            JsonNode i = JSON.readTree(info.toFile());
            JsonNode starts = i.path("week_starts");
            patchOnly = i.path("patch_only").asBoolean(false);
            patch = i.path("patch").asText(null);
            if (starts.size() > 0) {
                java.time.Instant end = java.time.Instant.parse(starts.get(starts.size() - 1).asText()).plusSeconds(6 * 86400);
                if (!i.path("fetched_at").asText("").isEmpty()) {
                    java.time.Instant fetched = java.time.Instant.parse(i.path("fetched_at").asText());
                    end = fetched.isBefore(end) ? fetched : end;
                }
                sample = dd(starts.get(0).asText()) + " — " + dd(end.toString())
                        + (patchOnly ? " (только патч " + patch + ")" : "") + ", Legend–Immortal, STRATZ";
            }
        }
        LaneStats s = new LaneStats(dir.getFileName().toString(), sample);
        s.patchOnly = patchOnly;
        s.patch = patch;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.getFileName().toString().matches("\\d+\\.json"))::iterator) {
                int hero = Integer.parseInt(f.getFileName().toString().replace(".json", ""));
                s.read(hero, JSON.readTree(f.toFile()).path("data").path("heroStats"));
            }
        }
        return s;
    }

    private void read(int hero, JsonNode stats) {
        Iterator<Map.Entry<String, JsonNode>> it = stats.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            Matcher m = ALIAS.matcher(e.getKey());
            if (m.matches()) {
                int pos = Integer.parseInt(m.group(2));
                for (JsonNode r : e.getValue()) {
                    Cell c = new Cell(r.path("matchCount").asInt(),
                            r.path("winCount").asInt() + r.path("stompWinCount").asInt(),
                            r.path("drawCount").asInt(),
                            r.path("lossCount").asInt() + r.path("stompLossCount").asInt(),
                            r.path("matchWinCount").asInt());
                    lanes.computeIfAbsent(hero, k -> new HashMap<>()).computeIfAbsent(pos, k -> new HashMap<>())
                            .merge(r.path("heroId2").asInt(), c, Cell::plus);
                    totals.computeIfAbsent(hero, k -> new HashMap<>()).merge(pos, c, Cell::plus);
                }
            } else if (MATCHUP.matcher(e.getKey()).matches()) {
                for (JsonNode a : e.getValue().path("advantage")) {
                    if (a.path("heroId").asInt() != hero) {
                        continue;
                    }
                    for (JsonNode v : a.path("vs")) {
                        add(game, hero, v);
                    }
                    for (JsonNode v : a.path("with")) {
                        add(allies, hero, v);
                    }
                }
            } else if (ITEMS.matcher(e.getKey()).matches()) {
                Matcher im = ITEMS.matcher(e.getKey());
                im.matches();
                int pos = Integer.parseInt(im.group(2));
                for (JsonNode r : e.getValue()) {
                    items.add(hero, pos, r.path("itemId").asInt(), r.path("instance").asInt(), r.path("time").asInt(),
                            r.path("matchCount").asLong(), r.path("winCount").asLong());
                }
            } else if (STATS.matcher(e.getKey()).matches()) {
                for (JsonNode r : e.getValue()) {
                    String p = r.path("position").asText("");
                    if (p.matches("POSITION_[1-5]")) {
                        long[] g = positions.computeIfAbsent(hero, k -> new long[6][2])[p.charAt(9) - '0'];
                        g[0] += r.path("matchCount").asLong();
                        g[1] += r.path("winCount").asLong();
                    }
                }
            }
        }
    }

    private static String dd(String instant) {
        return instant.substring(8, 10) + "." + instant.substring(5, 7);
    }

    private static void add(Map<Integer, Map<Integer, double[]>> to, int hero, JsonNode v) {
        double n = v.path("matchCount").asDouble();
        double[] cell = to.computeIfAbsent(hero, k -> new HashMap<>()).computeIfAbsent(v.path("heroId2").asInt(),
                k -> new double[3]);
        cell[0] += n;
        cell[1] += v.path("synergy").asDouble() * n;
        cell[2] += v.path("winCount").asDouble();
    }

    /** Синергия STRATZ (%, взвешенная по играм за недели) и игры; null, если пары нет. */
    private static double[] synergy(Map<Integer, Map<Integer, double[]>> from, int hero, int other) {
        double[] v = from.getOrDefault(hero, Map.of()).get(other);
        return v == null || v[0] == 0 ? null : new double[]{v[0], v[1] / v[0], v[2]};
    }

    /** Игры и победы героя на позиции за выборку STRATZ; {0, 0}, если нет. */
    long[] positionGames(int hero, int pos) {
        long[][] p = positions.get(hero);
        return p == null ? new long[2] : p[pos].clone();
    }

    /** Преимущество hero вместе с союзником ally (доля: 0,03 = +3%); NaN, если игр меньше {@link #MIN_GAMES}. */
    double allyAdvantage(int hero, int ally) {
        double[] v = synergy(allies, hero, ally);
        return v == null || v[0] < MIN_GAMES ? Double.NaN : v[1] / 100;
    }

    int allyGames(int hero, int ally) {
        double[] v = synergy(allies, hero, ally);
        return v == null ? 0 : (int) v[0];
    }

    boolean isEmpty() {
        return lanes.isEmpty();
    }

    Cell cell(int hero, int pos, int enemy) {
        return lanes.getOrDefault(hero, Map.of()).getOrDefault(pos, Map.of()).getOrDefault(enemy, Cell.EMPTY);
    }

    /** Сколько линий герой отстоял на позиции за выборку (все соперники). */
    int lanesAt(int hero, int pos) {
        return totals.getOrDefault(hero, Map.of()).getOrDefault(pos, Cell.EMPTY).matches();
    }

    /** Обычное (выиграно − проиграно) / линий героя на позиции — к нему сглаживаем пары с малым числом линий. */
    double baseAdvantage(int hero, int pos) {
        Cell t = totals.getOrDefault(hero, Map.of()).getOrDefault(pos, Cell.EMPTY);
        return t.matches() == 0 ? 0 : (double) (t.win() - t.loss()) / t.matches();
    }

    /**
     * Герой hero на позиции pos против героев enemies на его линии. Пары, где линий меньше {@link #MIN_LANES},
     * пропускаются; если не осталось ни одной — null.
     */
    Outcome lane(int hero, int pos, Collection<Integer> enemies) {
        Cell sum = Cell.EMPTY;
        for (int e : enemies) {
            Cell c = cell(hero, pos, e);
            if (c.matches() >= MIN_LANES) {
                sum = sum.plus(c);
            }
        }
        if (sum.matches() == 0) {
            return null;
        }
        double adv = (sum.win() - sum.loss() + PRIOR * baseAdvantage(hero, pos)) / (sum.matches() + PRIOR);
        return new Outcome(sum.matches(), sum.rate(sum.win()), sum.rate(sum.draw()), sum.rate(sum.loss()),
                sum.rate(sum.gameWins()), adv);
    }

    /** Преимущество hero по игре против enemies (среднее synergy, доля: 0,03 = +3%). NaN, если нет ни одной пары. */
    double gameAdvantage(int hero, Collection<Integer> enemies) {
        double sum = 0;
        int n = 0;
        for (int e : enemies) {
            double[] v = synergy(game, hero, e);
            if (v != null && v[0] >= MIN_GAMES) {
                sum += v[1] / 100;
                n++;
            }
        }
        return n == 0 ? Double.NaN : sum / n;
    }

    /** Игр hero против enemies в матчапах по игре (сумма). */
    int gameMatches(int hero, Collection<Integer> enemies) {
        int n = 0;
        for (int e : enemies) {
            double[] v = synergy(game, hero, e);
            n += v == null ? 0 : (int) v[0];
        }
        return n;
    }

    /** С кем на линии встречается наша позиция: 1 и 5 — против их 3 и 4, мид — против мида, 3 и 4 — против их 1 и 5. */
    static List<Integer> opposingPositions(int ourPos) {
        return switch (ourPos) {
            case 1, 5 -> List.of(3, 4);
            case 2 -> List.of(2);
            case 3, 4 -> List.of(1, 5);
            default -> List.of();
        };
    }
}
