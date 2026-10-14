package dota.fetch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Матчи лиги: data/raw/league/{id}/match_ids.json и matches/{match_id}.json.
 * <p>
 * Список match_id — индекс, который растёт по ходу турнира, поэтому он перекачивается при каждом запуске
 * (один запрос). Сами матчи неизменны и без --refresh не перекачиваются. На OpenDota лига общая для всех
 * сезонов, поэтому идём от новых матчей к старым и останавливаемся на первом матче раньше {@code since}.
 */
class LeagueFetcher {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final OpenDotaClient client;
    private final RawFetcher fetcher;
    private final boolean refresh;

    LeagueFetcher(OpenDotaClient client, RawFetcher fetcher, boolean refresh) {
        this.client = client;
        this.fetcher = fetcher;
        this.refresh = refresh;
    }

    void fetch(long leagueId, LocalDate since, Path dir) throws InterruptedException {
        long sinceEpoch = since.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        Log.info("== league %d since %s -> %s", leagueId, since, dir);
        List<Long> ids;
        try {
            byte[] body = client.get("/leagues/" + leagueId + "/matchIds");
            RawFetcher.checkJson(body);
            RawFetcher.writeAtomically(dir.resolve("match_ids.json"), body);
            ids = parseIds(body);
        } catch (IOException e) {
            fetcher.recordFailure("league " + leagueId + " match ids", e);
            return;
        }

        int saved = 0, skipped = 0;
        for (long id : ids) {
            Path file = dir.resolve("matches").resolve(id + ".json");
            try {
                byte[] body;
                if (!refresh && Files.exists(file)) {
                    body = Files.readAllBytes(file);
                    skipped++;
                } else {
                    body = client.get("/matches/" + id);
                    RawFetcher.checkJson(body);
                    if (startTime(body) < sinceEpoch) {
                        break;
                    }
                    RawFetcher.writeAtomically(file, body);
                    saved++;
                }
                if (startTime(body) < sinceEpoch) {
                    break;
                }
            } catch (IOException e) {
                fetcher.recordFailure("match " + id, e);
            }
        }
        fetcher.addCounts(saved, skipped);
        Log.info("league %d: saved %d matches, already had %d", leagueId, saved, skipped);
    }

    /** match_id растут со временем — сортируем по убыванию, чтобы остановиться на начале сезона. */
    static List<Long> parseIds(byte[] body) throws IOException {
        List<Long> ids = new ArrayList<>();
        JSON.readTree(body).forEach(n -> ids.add(n.asLong()));
        ids.sort(Comparator.reverseOrder());
        return ids;
    }

    static long startTime(byte[] matchBody) throws IOException {
        JsonNode start = JSON.readTree(matchBody).path("start_time");
        if (!start.canConvertToLong()) {
            throw new IOException("start_time not found in match");
        }
        return start.asLong();
    }
}
