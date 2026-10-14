package dota.web;

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

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Дымовой тест: все страницы отдаются с реальными data/ (пропускается, если данных нет). */
class WebAppTest {

    private static Javalin app;
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @BeforeAll
    static void start() throws Exception {
        assumeTrue(Files.isDirectory(Path.of("data/raw")), "нет data/ — сайт не на чем проверить");
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

    private HttpResponse<String> get(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void reportPagesRender() throws Exception {
        for (String path : new String[]{"/", "/team", "/player/138244651", "/meta", "/draft-data", "/tournament",
                "/group", "/opponent/UPPO_TEAM", "/method", "/static/app.css"}) {
            HttpResponse<String> r = get(path);
            assertEquals(200, r.statusCode(), path);
            assertFalse(r.body().isBlank(), path);
        }
    }

    @Test
    void homeShowsNextOpponentAndGroup() throws Exception {
        String body = get("/").body();
        assertTrue(body.contains("UPPO TEAM"));
        assertTrue(body.contains("Team Visual"));
    }

    @Test
    void unknownPlayerIs404() throws Exception {
        assertEquals(404, get("/player/1").statusCode());
    }
}
