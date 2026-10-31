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
    /** hero → соперник → {игр, synergy %}. */
    private final Map<Integer, Map<Integer, double[]>> game = new HashMap<>();

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
        Path info = dir.resolve("_sample.json");
        if (Files.exists(info)) {
            JsonNode i = JSON.readTree(info.toFile());
            JsonNode starts = i.path("week_starts");
            if (starts.size() > 0) {
                sample = starts.get(0).asText().substring(0, 10) + " — "
                        + java.time.Instant.parse(starts.get(starts.size() - 1).asText()).plusSeconds(6 * 86400)
                        .toString().substring(0, 10) + ", Legend–Immortal, STRATZ";
            }
        }
        LaneStats s = new LaneStats(dir.getFileName().toString(), sample);
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
            } else if (e.getKey().equals("mu")) {
                for (JsonNode a : e.getValue().path("advantage")) {
                    if (a.path("heroId").asInt() != hero) {
                        continue;
                    }
                    for (JsonNode v : a.path("vs")) {
                        game.computeIfAbsent(hero, k -> new HashMap<>()).put(v.path("heroId2").asInt(),
                                new double[]{v.path("matchCount").asDouble(), v.path("synergy").asDouble()});
                    }
                }
            }
        }
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
            double[] v = game.getOrDefault(hero, Map.of()).get(e);
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
            double[] v = game.getOrDefault(hero, Map.of()).get(e);
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
