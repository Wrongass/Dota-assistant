package dota.web;

import dota.analysis.Lineups;
import dota.analysis.Views;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Состав на игру: сохранение с сайта применяется везде, сброс возвращает конфиг, ошибки ввода не сохраняются. */
class LineupTest {

    private static final long KREN = 168928320, WRONGASS = 138244651, ARTIST = 149535595, BABAYKA = 137322907,
            ENARIA = 1532851914, DAVAJJ = 108859089;
    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @TempDir
    static Path state;
    private static Site site;
    private static Javalin app;

    @BeforeAll
    static void start() throws Exception {
        assumeTrue(Files.isDirectory(Path.of("data/raw")), "нет data/");
        site = new Site(Path.of("config/players.yaml"), Path.of("data"), Path.of("analysis"), state.resolve("lineups.json"));
        site.reload();
        app = new WebApp(site).create().start("127.0.0.1", 0);
    }

    @AfterAll
    static void stop() {
        if (app != null) {
            app.stop();
        }
    }

    private static Map<Integer, Long> withDavajj() {
        Map<Integer, Long> m = new HashMap<>(Map.of(1, KREN, 2, WRONGASS, 3, DAVAJJ, 4, BABAYKA, 5, ENARIA));
        return m;
    }

    private static int positionOf(Views.Lineup l, long id) {
        return l.players().stream().filter(p -> p.accountId() == id).findFirst().orElseThrow().position();
    }

    @Test
    void reserveIsOnBenchUntilSavedAndBackAfterReset() throws Exception {
        String team = site.ctx().ours.team().name();
        Views.Lineup byConfig = site.ctx().ours.lineup();
        assertFalse(byConfig.changed());
        assertEquals(3, positionOf(byConfig, ARTIST));
        assertEquals(0, positionOf(byConfig, DAVAJJ), "запасной не в составе");

        site.saveLineup(team, withDavajj());
        Views.Lineup saved = site.ctx().ours.lineup();
        assertTrue(saved.changed());
        assertEquals(3, positionOf(saved, DAVAJJ));
        assertEquals(0, positionOf(saved, ARTIST), "Артист ушёл в запасные");
        assertTrue(Files.readString(state.resolve("lineups.json")).contains(String.valueOf(DAVAJJ)));
        assertNotNull(site.ctx().squadOf(ARTIST), "страница запасного по-прежнему открывается");
        assertNotNull(Lineups.load(state.resolve("lineups.json")).of(team), "состав пережил перезапуск");

        site.saveLineup(team, null);
        assertFalse(site.ctx().ours.lineup().changed());
        assertEquals(3, positionOf(site.ctx().ours.lineup(), ARTIST));
    }

    @Test
    void badLineupsAreRejected() {
        String team = site.ctx().ours.team().name();
        Map<Integer, Long> twice = withDavajj();
        twice.put(4, DAVAJJ);
        assertThrows(IllegalArgumentException.class, () -> site.saveLineup(team, twice));
        Map<Integer, Long> stranger = withDavajj();
        stranger.put(5, 42L);
        assertThrows(IllegalArgumentException.class, () -> site.saveLineup(team, stranger));
        assertThrows(IllegalArgumentException.class, () -> site.saveLineup("нет такой", withDavajj()));
        assertFalse(site.ctx().ours.lineup().changed());
    }

    @Test
    void unwritableStateShowsReasonInsteadOfServerError() throws Exception {
        Path notADir = Files.writeString(state.resolve("file-not-dir"), "x");
        Site broken = new Site(Path.of("config/players.yaml"), Path.of("data"), Path.of("analysis"),
                notADir.resolve("lineups.json"));
        broken.reload();
        Javalin other = new WebApp(broken).create().start("127.0.0.1", 0);
        try {
            String team = java.net.URLEncoder.encode(broken.ctx().ours.team().name(), java.nio.charset.StandardCharsets.UTF_8);
            StringBuilder form = new StringBuilder("team=" + team + "&back=%2Fteam&action=save");
            withDavajj().forEach((pos, id) -> form.append("&p").append(pos).append("=").append(id));
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + other.port() + "/lineup"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form.toString())).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(302, r.statusCode(), "не 500, а возврат на страницу с причиной");
            assertTrue(r.headers().firstValue("Location").orElse("").startsWith("/team?lineupError="));
            assertTrue(WebApp.lineupsStatus(notADir.resolve("lineups.json")).startsWith("ВНИМАНИЕ"));
            assertTrue(WebApp.lineupsStatus(state.resolve("lineups.json")).startsWith("Состав на игру сохраняется"));
        } finally {
            other.stop();
        }
    }

    @Test
    void formSavesAndRedirectsBack() throws Exception {
        String team = site.ctx().ours.team().name();
        StringBuilder form = new StringBuilder("team=" + java.net.URLEncoder.encode(team, java.nio.charset.StandardCharsets.UTF_8)
                + "&back=%2Fteam&action=save");
        withDavajj().forEach((pos, id) -> form.append("&p").append(pos).append("=").append(id));
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/lineup"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form.toString())).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(302, r.statusCode());
        assertEquals("/team", r.headers().firstValue("Location").orElse(""));
        assertTrue(site.ctx().ours.lineup().changed());
        String teamPage = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/team")).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        assertTrue(teamPage.contains("изменён на сайте"));
        site.saveLineup(team, null);
    }

    @Test
    void crossSiteFormPostIsRejected() throws Exception {
        String team = java.net.URLEncoder.encode(site.ctx().ours.team().name(), java.nio.charset.StandardCharsets.UTF_8);
        StringBuilder form = new StringBuilder("team=" + team + "&back=%2Fteam&action=save");
        withDavajj().forEach((pos, id) -> form.append("&p").append(pos).append("=").append(id));
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/lineup"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-Forwarded-Host", "dota.example") // как за прокси с доменом
                .POST(HttpRequest.BodyPublishers.ofString(form.toString()));
        HttpResponse<String> evil = HTTP.send(req.copy().header("Origin", "https://evil.example").build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, evil.statusCode(), "чужой сайт");
        assertFalse(site.ctx().ours.lineup().changed(), "состав не изменился");
        HttpResponse<String> same = HTTP.send(req.copy().header("Origin", "https://dota.example").build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(302, same.statusCode(), "свой сайт");
        site.saveLineup(site.ctx().ours.team().name(), null);
    }

    @Test
    void savedDraftAppearsInHistory() throws Exception {
        String body = "{\"opponent\":\"SD_Team\",\"map\":1,\"weFirst\":true,\"heroes\":[23,22,1,2,31,32,33,42,106],\"assign\":{}}";
        HttpResponse<String> saved = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/api/share"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, saved.statusCode(), saved.body());
        String page = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/drafts")).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        assertTrue(page.contains("SD Team") && page.contains("/draft?share="), "драфт в истории");
    }
}

