package dota.fetch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Общая картина текущего патча: data/meta/{patch}/*.json.
 * <ul>
 *   <li>hero_stats — /heroStats: пики/победы по рангам 1..8 (Herald..Immortal), pro pick/ban, тренд за 7 дней;</li>
 *   <li>lane_roles — /scenarios/laneRoles: винрейт героя по линии и длительности игры;</li>
 *   <li>hero_winrate, hero_synergy, hero_counters — SQL через /explorer по выборке public_matches
 *       (последние {@code window} match_id, средний ранг ≥ {@code minRankTier}, только текущий патч).</li>
 * </ul>
 */
final class MetaEndpoints {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Параметры выборки для explorer-запросов. */
    record Sample(long maxMatchId, long window, int minRankTier, long patchStartEpoch) {

        String where() {
            return "match_id > " + (maxMatchId - window)
                    + " and avg_rank_tier >= " + minRankTier
                    + " and start_time >= " + patchStartEpoch;
        }
    }

    /** Текущий патч — последний элемент constants/patch.json. */
    record Patch(String name, Instant start) {
    }

    private MetaEndpoints() {
    }

    static Patch currentPatch(Path patchJson) throws IOException {
        JsonNode patches = JSON.readTree(patchJson.toFile());
        JsonNode last = patches.get(patches.size() - 1);
        return new Patch(last.get("name").asText(), Instant.parse(last.get("date").asText()));
    }

    static String maxMatchIdUrl() {
        return explorer("select max(match_id) as max_match_id from public_matches");
    }

    static long parseMaxMatchId(byte[] body) throws IOException {
        JsonNode value = JSON.readTree(body).path("rows").path(0).path("max_match_id");
        if (!value.canConvertToLong()) {
            throw new IOException("max_match_id not found in explorer response");
        }
        return value.asLong();
    }

    /** Файлы, которые не зависят от выборки: их можно проверить на наличие до запроса max(match_id). */
    static List<Endpoints.Target> staticTargets(Path dir) {
        return List.of(
                new Endpoints.Target("/heroStats", dir.resolve("hero_stats.json")),
                new Endpoints.Target("/scenarios/laneRoles", dir.resolve("lane_roles.json")));
    }

    static List<Path> explorerFiles(Path dir) {
        return List.of(dir.resolve("hero_winrate.json"), dir.resolve("hero_synergy.json"),
                dir.resolve("hero_counters.json"));
    }

    static List<Endpoints.Target> explorerTargets(Path dir, Sample s) {
        List<Path> files = explorerFiles(dir);
        return List.of(
                new Endpoints.Target(explorer(heroWinrateSql(s)), files.get(0)),
                new Endpoints.Target(explorer(synergySql(s)), files.get(1)),
                new Endpoints.Target(explorer(countersSql(s)), files.get(2)));
    }

    /** Базовый винрейт героя в той же выборке — чтобы считать «прибавку» связки относительно ожидаемого. */
    static String heroWinrateSql(Sample s) {
        return teamsCte(s) + """
                select h.hero_id, count(*) as games, sum(case when win then 1 else 0 end) as wins
                from t, unnest(team) as h(hero_id)
                group by 1 order by 1""";
    }

    /** Пары героев в одной команде; h1 < h2, wins — победы этой пары. */
    static String synergySql(Sample s) {
        return teamsCte(s) + """
                select a.h as h1, b.h as h2, count(*) as games, sum(case when win then 1 else 0 end) as wins
                from t, unnest(team) as a(h), unnest(team) as b(h)
                where a.h < b.h
                group by 1, 2 having count(*) >= 30 order by 1, 2""";
    }

    /** Герой h1 против героя h2 в команде соперника; wins — победы h1. Каждая пара есть в обе стороны. */
    static String countersSql(Sample s) {
        return """
                with pm as (select radiant_win, radiant_team, dire_team from public_matches where %s),
                t as (select radiant_team as team, dire_team as enemy, radiant_win as win from pm
                      union all
                      select dire_team, radiant_team, not radiant_win from pm)
                select a.h as h1, b.h as h2, count(*) as games, sum(case when win then 1 else 0 end) as wins
                from t, unnest(team) as a(h), unnest(enemy) as b(h)
                group by 1, 2 having count(*) >= 30 order by 1, 2""".formatted(s.where());
    }

    private static String teamsCte(Sample s) {
        return """
                with pm as (select radiant_win, radiant_team, dire_team from public_matches where %s),
                t as (select radiant_team as team, radiant_win as win from pm
                      union all
                      select dire_team, not radiant_win from pm)
                """.formatted(s.where());
    }

    private static String explorer(String sql) {
        return "/explorer?sql=" + URLEncoder.encode(sql.replaceAll("\\s+", " ").trim(), StandardCharsets.UTF_8);
    }

    /** Описание выборки рядом с данными: без него hero_synergy.json и т.п. невозможно правильно прочитать. */
    static void writeInfo(Path dir, Patch patch, Sample s) throws IOException {
        var info = JSON.createObjectNode();
        info.put("patch", patch.name());
        info.put("patch_start", patch.start().toString());
        info.put("fetched_at", Instant.now().toString());
        info.put("max_match_id", s.maxMatchId());
        info.put("match_id_window", s.window());
        info.put("min_avg_rank_tier", s.minRankTier());
        info.put("where", s.where());
        info.put("note", "hero_winrate/synergy/counters: public_matches sample via /explorer; "
                + "hero_stats and lane_roles: OpenDota's own recent aggregates (not filtered by us)");
        RawFetcher.writeAtomically(dir.resolve("_sample.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(info));
    }

    static boolean allExist(List<Path> files) {
        return files.stream().allMatch(Files::exists);
    }
}
