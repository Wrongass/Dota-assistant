package dota.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Симуляция драфта через API: бот ходит за соперника, в конце план бота и оценка. */
class SimApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static Javalin app;

    @BeforeAll
    static void start() throws Exception {
        assumeTrue(Files.isDirectory(Path.of("data/raw")), "нет data/");
        Site site = new Site(Path.of("config/players.yaml"), Path.of("data"), Path.of("analysis"));
        site.reload();
        app = new WebApp(site).create().start("127.0.0.1", 0);
    }

    @AfterAll
    static void stop() {
        if (app != null) {
            app.stop();
        }
    }

    private static HttpResponse<String> post(String path, Object body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void botPlaysTheOpponentAndTheDraftIsEvaluated() throws Exception {
        String opp = JSON.readTree(post("/api/draft", java.util.Map.of("opponent", "SD_Team", "weFirst", true,
                "heroes", List.of())).body()).path("theirRoster").isArray() ? "SD_Team" : null;
        assertNotNull(opp);
        List<Integer> heroes = new ArrayList<>();
        while (heroes.size() < 24) {
            var body = java.util.Map.of("opponent", opp, "weFirst", true, "heroes", heroes, "seed", 42);
            boolean ours = dota.analysis.DraftEngine.SLOTS.get(heroes.size()).firstTeam();
            if (ours) {
                JsonNode a = JSON.readTree(post("/api/draft", body).body());
                JsonNode list = a.path(a.path("pickNext").asBoolean() ? "pickSuggestions" : "banSuggestions");
                int h = list.size() > 0 ? list.get(0).path("heroId").asInt() : 0;
                for (int x = 1; h == 0 || heroes.contains(h); x++) {
                    h = x; // советов нет (бывает на последних ходах) — любой свободный герой
                }
                heroes.add(h);
            } else {
                HttpResponse<String> r = post("/api/sim/move", body);
                assertEquals(200, r.statusCode(), r.body());
                heroes.add(JSON.readTree(r.body()).path("heroId").asInt());
            }
        }
        var done = java.util.Map.of("opponent", opp, "weFirst", true, "heroes", heroes, "seed", 42);
        assertEquals(409, post("/api/sim/move", done).statusCode(), "после конца драфта ходов нет");
        JsonNode plan = JSON.readTree(post("/api/sim/plan", done).body());
        assertEquals(5, plan.path("placements").size());
        JsonNode ev = JSON.readTree(post("/api/sim/evaluate", done).body());
        assertEquals(3, ev.path("lanes").size());
        assertEquals(6, ev.path("sections").size());
        assertFalse(ev.path("total").path("verdict").asText().isEmpty());
    }

    @Test
    void badRequestsAreRejected() throws Exception {
        assertEquals(400, post("/api/sim/move", java.util.Map.of("opponent", "nobody", "weFirst", true)).statusCode());
        assertEquals(409, post("/api/sim/move", java.util.Map.of("opponent", "SD_Team", "weFirst", true,
                "heroes", List.of())).statusCode(), "первый ход — наш");
        assertEquals(409, post("/api/sim/evaluate", java.util.Map.of("opponent", "SD_Team", "weFirst", true,
                "heroes", List.of(1, 2))).statusCode(), "оценка только законченного драфта");
    }
}
