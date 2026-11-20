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
 * Линия, матчапы и предметы из STRATZ: для каждого героя один запрос {@code heroStats} с алиасами
 * <ul>
 *   <li>{@code w{неделя}p{позиция}} — {@code laneOutcome}: исход линии героя на позиции против каждого героя,
 *       который стоял с ним на линии (выиграна, ничья, проиграна, разгромы, выиграна игра), за неделю;</li>
 *   <li>{@code mu{неделя}} — {@code heroVsHeroMatchup}: преимущество героя против каждого героя по игре в целом
 *       ({@code vs}) и вместе с каждым союзником ({@code with});</li>
 *   <li>{@code i{неделя}p{позиция}} — {@code itemFullPurchase}: покупки готовых предметов по минутам и победы;</li>
 *   <li>{@code s{неделя}} — {@code stats}: игры и победы героя по позициям.</li>
 * </ul>
 * Ранги Legend–Immortal. Недели (номер с 1970 года, как у STRATZ) — только те, что начались после выхода текущего
 * патча, не больше {@link #WEEKS}. Если целой недели после патча ещё нет — последние {@link #WEEKS} недели прошлого
 * патча. Файл: {@code data/raw/stratz/lanes/{последняя неделя}/{hero_id}.json}, ответ сохраняется как есть.
 * Идущая неделя ещё набирает игры, поэтому её папка перекачивается, если она старше {@link #STALE_HOURS} часов.
 */
final class StratzFetcher {

    static final int WEEKS = 4;
    static final int STALE_HOURS = 20;
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

    /**
     * Недели текущего патча: начались не раньше patchStart (неделя, в которую вышел патч, смешала бы два патча).
     * Если таких нет (патч вышел на этой неделе) — последние {@link #WEEKS} недели целиком.
     */
    static List<Long> patchWeeks(long lastWeek, Instant patchStart) {
        List<Long> weeks = weeks(lastWeek).stream()
                .filter(w -> patchStart == null || w * WEEK_SECONDS >= patchStart.getEpochSecond()).toList();
        return weeks.isEmpty() ? weeks(lastWeek) : weeks;
    }

    static String query(int heroId, List<Long> weeks) {
        String brackets = String.join(",", BRACKETS);
        StringBuilder q = new StringBuilder("{ heroStats {\n");
        for (long w : weeks) {
            long week = w * WEEK_SECONDS;
            for (int pos = 1; pos <= 5; pos++) {
                q.append(String.format(
                        "  w%dp%d: laneOutcome(heroId: %d, isWith: false, week: %d, positionIds: [POSITION_%d], "
                                + "bracketBasicIds: [%s]) { %s }%n",
                        w, pos, heroId, week, pos, brackets, LANE_FIELDS));
                q.append(String.format(
                        "  i%dp%d: itemFullPurchase(heroId: %d, week: %d, positionIds: [POSITION_%d], "
                                + "bracketBasicIds: [%s]) { itemId instance time matchCount winCount }%n",
                        w, pos, heroId, week, pos, brackets));
            }
            q.append(String.format("  mu%d: heroVsHeroMatchup(heroId: %d, week: %d, bracketBasicIds: [%s]) "
                    + "{ advantage { heroId matchCountVs matchCountWith vs { heroId2 matchCount winCount synergy } "
                    + "with { heroId2 matchCount winCount synergy } } }%n", w, heroId, week, brackets));
            q.append(String.format("  s%d: stats(heroIds: [%d], week: %d, bracketBasicIds: [%s], groupByPosition: true) "
                    + "{ position matchCount winCount }%n", w, heroId, week, brackets));
        }
        return q.append("} }").toString();
    }

    /**
     * Папку надо скачать заново: в ней другие недели (например, до выхода патча там были недели прошлого), или
     * неделя ещё шла при скачивании и с тех пор прошло больше {@link #STALE_HOURS} часов.
     */
    static boolean stale(JsonNode sample, List<Long> weeks, Instant now) {
        if (sample == null || sample.path("fetched_at").asText("").isEmpty()) {
            return false;
        }
        Instant fetched = Instant.parse(sample.path("fetched_at").asText());
        List<Long> had = new ArrayList<>();
        sample.path("weeks").forEach(w -> had.add(w.asLong()));
        long weekEnd = (weeks.get(weeks.size() - 1) + 1) * WEEK_SECONDS;
        return !had.equals(weeks)
                || fetched.getEpochSecond() < weekEnd && fetched.isBefore(now.minusSeconds(STALE_HOURS * 3600L));
    }

    void fetch(Path heroesJson, Path patchJson, Path stratzDir, Instant now) throws InterruptedException {
        List<Integer> heroes;
        MetaEndpoints.Patch patch = null;
        try {
            heroes = heroIds(heroesJson);
            if (Files.exists(patchJson)) {
                patch = MetaEndpoints.currentPatch(patchJson);
            }
        } catch (IOException e) {
            counts.recordFailure("stratz: cannot read " + heroesJson + " / " + patchJson, e);
            return;
        }
        Instant patchStart = patch == null ? null : patch.start();
        long lastWeek = weekOf(now);
        List<Long> weeks = patchWeeks(lastWeek, patchStart);
        boolean patchOnly = patchStart != null && weeks.get(0) * WEEK_SECONDS >= patchStart.getEpochSecond();
        Path dir = stratzDir.resolve("lanes").resolve(String.valueOf(lastWeek));
        boolean again = refresh;
        Path info = dir.resolve("_sample.json");
        try {
            if (!again && Files.exists(info) && stale(JSON.readTree(info.toFile()), weeks, now)) {
                Log.info("STRATZ %s: older than %dh while the week is running (or other weeks), fetching again",
                        dir, STALE_HOURS);
                again = true;
            }
        } catch (IOException | RuntimeException e) {
            again = true;
        }
        Log.info("== STRATZ lanes, items, matchups, weeks %s (%s) -> %s", weeks,
                patchOnly ? "patch " + patch.name() + " only" : "no full week of the new patch yet", dir);
        int downloaded = 0, skipped = 0;
        for (int h : heroes) {
            Path file = dir.resolve(h + ".json");
            if (!again && Files.exists(file)) {
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
        if (downloaded == 0 && Files.exists(info)) {
            return; // ничего не скачали — описание выборки остаётся прежним
        }
        try {
            ObjectNode node = JSON.createObjectNode();
            node.put("fetched_at", now.toString());
            if (patch != null) {
                node.put("patch", patch.name());
                node.put("patch_start", patch.start().toString());
            }
            node.put("patch_only", patchOnly);
            node.putPOJO("weeks", weeks);
            node.putPOJO("week_starts", weeks.stream().map(w -> Instant.ofEpochSecond(w * WEEK_SECONDS).toString()).toList());
            node.putPOJO("brackets", BRACKETS);
            node.put("note", "laneOutcome: hero at position vs each lane opponent; win/draw/loss are exclusive of "
                    + "stompWin/stompLoss; matchWinCount = games won. mu{week}: heroVsHeroMatchup, synergy = advantage "
                    + "in % (vs: against, with: allies). i{week}p{pos}: itemFullPurchase, one row per purchase minute, "
                    + "STRATZ drops small rows. s{week}: hero games and wins by position");
            RawFetcher.writeAtomically(info, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(node));
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
