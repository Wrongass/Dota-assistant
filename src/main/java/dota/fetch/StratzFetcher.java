package dota.fetch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Линия и матчапы из STRATZ: для каждого героя один запрос {@code heroStats} с алиасами
 * <ul>
 *   <li>{@code w{неделя}p{позиция}} — {@code laneOutcome}: исход линии героя на позиции против каждого героя,
 *       который стоял с ним на линии (выиграна, ничья, проиграна, разгромы, выиграна игра), за неделю;</li>
 *   <li>{@code mu} — {@code heroVsHeroMatchup}: преимущество героя против каждого героя по игре в целом.</li>
 * </ul>
 * Ранги Legend–Immortal, последние {@link #WEEKS} недели (неделя — номер с 1970 года, как у STRATZ).
 * Файл: {@code data/raw/stratz/lanes/{последняя неделя}/{hero_id}.json}, ответ сохраняется как есть.
 * На новой неделе — новая папка, поэтому раз в неделю данные обновляются без --refresh.
 */
final class StratzFetcher {

    static final int WEEKS = 4;
    static final List<String> BRACKETS = List.of("LEGEND_ANCIENT", "DIVINE_IMMORTAL");
    static final long WEEK_SECONDS = 7 * 86400L;
    private static final String LANE_FIELDS =
            "heroId2 matchCount winCount drawCount lossCount stompWinCount stompLossCount matchWinCount";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final StratzClient client;
    private final RawFetcher counts;
    private final boolean refresh;

    StratzFetcher(StratzClient client, RawFetcher counts, boolean refresh) {
        this.client = client;
        this.counts = counts;
        this.refresh = refresh;
    }

    static long weekOf(Instant t) {
        return t.getEpochSecond() / WEEK_SECONDS;
    }

    static List<Long> weeks(long lastWeek) {
        List<Long> weeks = new ArrayList<>();
        for (long w = lastWeek - WEEKS + 1; w <= lastWeek; w++) {
            weeks.add(w);
        }
        return weeks;
    }

    static String query(int heroId, List<Long> weeks) {
        String brackets = String.join(",", BRACKETS);
        StringBuilder q = new StringBuilder("{ heroStats {\n");
        for (long w : weeks) {
            for (int pos = 1; pos <= 5; pos++) {
                q.append(String.format(
                        "  w%dp%d: laneOutcome(heroId: %d, isWith: false, week: %d, positionIds: [POSITION_%d], "
                                + "bracketBasicIds: [%s]) { %s }%n",
                        w, pos, heroId, w * WEEK_SECONDS, pos, brackets, LANE_FIELDS));
            }
        }
        q.append(String.format("  mu: heroVsHeroMatchup(heroId: %d, bracketBasicIds: [%s]) "
                + "{ advantage { heroId matchCountVs vs { heroId2 matchCount winCount synergy } } }%n", heroId, brackets));
        return q.append("} }").toString();
    }

    void fetch(Path heroesJson, Path stratzDir, Instant now) throws InterruptedException {
        List<Integer> heroes;
        try {
            heroes = heroIds(heroesJson);
        } catch (IOException e) {
            counts.recordFailure("stratz: cannot read " + heroesJson, e);
            return;
        }
        long lastWeek = weekOf(now);
        List<Long> weeks = weeks(lastWeek);
        Path dir = stratzDir.resolve("lanes").resolve(String.valueOf(lastWeek));
        Log.info("== STRATZ lanes, weeks %s -> %s", weeks, dir);
        int downloaded = 0, skipped = 0;
        for (int h : heroes) {
            Path file = dir.resolve(h + ".json");
            if (!refresh && Files.exists(file)) {
                skipped++;
                continue;
            }
            try {
                byte[] body = client.query("hero " + h, query(h, weeks));
                checkGraphql(body);
                RawFetcher.writeAtomically(file, body);
                downloaded++;
            } catch (IOException e) {
                counts.recordFailure("stratz hero " + h, e);
            } catch (IllegalStateException e) {
                counts.recordFailure("stratz", e);
                break;
            }
        }
        if (skipped > 0) {
            Log.info("exists, skip: %d STRATZ hero files in %s", skipped, dir);
        }
        counts.addCounts(downloaded, skipped);
        try {
            ObjectNode info = JSON.createObjectNode();
            info.put("fetched_at", now.toString());
            info.putPOJO("weeks", weeks);
            info.putPOJO("week_starts", weeks.stream().map(w -> Instant.ofEpochSecond(w * WEEK_SECONDS).toString()).toList());
            info.putPOJO("brackets", BRACKETS);
            info.put("note", "laneOutcome: hero at position vs each lane opponent; win/draw/loss are exclusive of "
                    + "stompWin/stompLoss; matchWinCount = games won. mu: heroVsHeroMatchup, synergy = advantage in %");
            RawFetcher.writeAtomically(dir.resolve("_sample.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(info));
        } catch (IOException e) {
            counts.recordFailure("stratz: _sample.json", e);
        }
    }

    /** GraphQL отвечает 200 и при ошибке: {"errors": [...]} — такой ответ не сохраняем. */
    static void checkGraphql(byte[] body) throws IOException {
        JsonNode node = JSON.readTree(body);
        if (node == null || !node.path("errors").isMissingNode() || node.path("data").path("heroStats").isMissingNode()) {
            String s = new String(body, java.nio.charset.StandardCharsets.UTF_8);
            throw new IOException("GraphQL error: " + (s.length() > 300 ? s.substring(0, 300) + "…" : s));
        }
    }

    static List<Integer> heroIds(Path heroesJson) throws IOException {
        List<Integer> ids = new ArrayList<>();
        JSON.readTree(heroesJson.toFile()).fieldNames().forEachRemaining(k -> ids.add(Integer.parseInt(k)));
        return ids.stream().sorted().collect(Collectors.toList());
    }
}
