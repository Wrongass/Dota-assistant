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
                "/group", "/opponent/UPPO_TEAM", "/method", "/heroes", "/hero/12", "/static/app.css"}) {
            HttpResponse<String> r = get(path);
            assertEquals(200, r.statusCode(), path);
            assertFalse(r.body().isBlank(), path);
        }
    }

    @Test
    void patchPageAndApi() throws Exception {
        assertEquals(200, get("/patch").statusCode());
        assumeTrue(Files.isDirectory(Path.of("analysis/patch")), "нет разбора патча");
        HttpResponse<String> api = get("/api/patch");
        assertEquals(200, api.statusCode());
        assertTrue(api.body().contains("\"heroes\"") && api.body().contains("\"items\""), "в разборе есть герои и предметы");
    }

    @Test
    void patchVersionsCompareAsNumbers() {
        assertTrue(WebApp.versionKey("7.42").compareTo(WebApp.versionKey("7.9")) > 0, "7.42 новее 7.9");
        assertTrue(WebApp.versionKey("7.42b").compareTo(WebApp.versionKey("7.42")) > 0, "7.42b новее 7.42");
        assertTrue(WebApp.versionKey("7.42").compareTo(WebApp.versionKey("7.41c")) > 0);
    }

    @Test
    void homeShowsNextOpponentAndGroup() throws Exception {
        String body = get("/").body();
        assertTrue(body.contains("UPPO TEAM"));
        assertTrue(body.contains("Team Visual"));
    }

    @Test
    void draftPageAndApiWork() throws Exception {
        assertEquals(200, get("/draft?opponent=UPPO_TEAM").statusCode());
        long started = System.nanoTime();
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/api/draft"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"opponent\":\"UPPO_TEAM\",\"weFirst\":true,\"heroes\":[23,22,1,2,31,32,33,42,25]}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        long ms = (System.nanoTime() - started) / 1_000_000;
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("theirNextPicks"));
        assertTrue(r.body().contains("\"theirAssignment\""));
        assertTrue(ms < 5000, "ответ API " + ms + " мс");
    }

    @Test
    void draftApiRejectsUnknownOpponent() throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/api/draft"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"opponent\":\"nope\",\"weFirst\":true,\"heroes\":[]}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, r.statusCode());
    }

    @Test
    void planLinksPointToSitePagesNotMarkdownFiles() throws Exception {
        for (String path : new String[]{"/draft-data", "/opponent/UPPO_TEAM"}) {
            String body = get(path).body();
            assertFalse(body.matches("(?s).*href=\"[^\"]*\\.md[\"#].*"), path + " содержит ссылку на .md");
        }
        assertTrue(get("/draft-data").body().contains("href=\"/group\""), "ссылка ../league/group.md → /group");
        String future = get("/opponent/Team_Future").body();
        if (future.contains("предметам патча")) {
            assertTrue(future.contains("href=\"/heroes\""), "ссылка ../../items/7.42/items.md → /heroes");
        }
    }

    @Test
    void unknownPlayerIs404() throws Exception {
        assertEquals(404, get("/player/1").statusCode());
    }

    @Test
    void badAddressesGiveSitePagesNotServerErrors() throws Exception {
        HttpResponse<String> byNick = get("/player/kren");
        assertEquals(200, byNick.statusCode(), "игрок по нику из конфига");
        assertTrue(byNick.body().contains("kren"));

        for (String path : new String[]{"/player/99999999999999999999", "/player/nobody", "/opponent/NOPE_TEAM", "/nonexistent"}) {
            HttpResponse<String> r = get(path);
            assertEquals(404, r.statusCode(), path);
            assertTrue(r.headers().firstValue("Content-Type").orElse("").toLowerCase().contains("utf-8"), path);
            assertTrue(r.body().contains("Не найдено") && r.body().contains("На главную"), path + ": страница сайта");
        }
        HttpResponse<String> api = get("/api/player/nobody");
        assertEquals(404, api.statusCode());
        assertTrue(api.body().startsWith("{"), "для /api — JSON");

        HttpResponse<String> lower = get("/opponent/sd_team");
        assertEquals(302, lower.statusCode(), "id соперника без учёта регистра");
        assertEquals("/opponent/SD_Team", lower.headers().firstValue("Location").orElse(""));

        HttpResponse<String> pos = get("/counters?h=Sniper&pos=abc");
        assertEquals(200, pos.statusCode());
        assertTrue(pos.body().contains("такой нет, показываю «авто»"));
    }
}

