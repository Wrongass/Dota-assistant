package dota.web;

import io.javalin.Javalin;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AuthTest {

    private static final Clock NOW = Clock.fixed(Instant.parse("2026-10-14T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void disabledWithoutPassword() {
        Auth auth = new Auth(null, NOW);
        assertFalse(auth.enabled());
        assertTrue(auth.validToken(null), "без пароля вход не нужен");
    }

    @Test
    void tokenIsSignedAndExpires() {
        Auth auth = new Auth("secret", NOW);
        String token = auth.newToken();
        assertTrue(auth.validToken(token));
        assertFalse(auth.validToken(null));
        assertFalse(auth.validToken("garbage"));
        // подделанный срок с чужой подписью не проходит
        String[] parts = token.split("\\.");
        assertFalse(auth.validToken((Long.parseLong(parts[0]) + 1000) + "." + parts[1]));
        // другой пароль — другой ключ: старые сессии недействительны
        assertFalse(new Auth("other", NOW).validToken(token));
        // через 31 день сессия истекла
        Auth later = new Auth("secret", Clock.offset(NOW, Duration.ofDays(31)));
        assertFalse(later.validToken(token));
    }

    @Test
    void passwordCheck() {
        Auth auth = new Auth("secret", NOW);
        assertTrue(auth.checkPassword("secret"));
        assertFalse(auth.checkPassword("Secret"));
        assertFalse(auth.checkPassword(null));
    }

    @Test
    void nextMustBeLocalPath() {
        assertEquals("/draft?opponent=X", Auth.safeNext("/draft?opponent=X"));
        assertEquals("/", Auth.safeNext("//evil.example"));
        assertEquals("/", Auth.safeNext("https://evil.example"));
        assertEquals("/", Auth.safeNext("/\\evil.example"));
        assertEquals("/", Auth.safeNext(null));
    }

    @Test
    void rateLimitsFailures() {
        Auth auth = new Auth("secret", NOW);
        for (int i = 0; i < 5; i++) {
            assertFalse(auth.tooManyFailures("1.2.3.4"));
            auth.recordFailure("1.2.3.4");
        }
        assertTrue(auth.tooManyFailures("1.2.3.4"));
        assertFalse(auth.tooManyFailures("5.6.7.8"), "лимит — на адрес");
    }

    @Test
    void loginFlowOnRealServer() throws Exception {
        assumeTrue(Files.isDirectory(Path.of("data/raw")), "нет data/");
        Site site = new Site(Path.of("config/players.yaml"), Path.of("data"), Path.of("analysis"));
        site.reload();
        Javalin app = new WebApp(site, new Auth("secret", Clock.systemUTC())).create().start("127.0.0.1", 0);
        try {
            HttpClient http = HttpClient.newHttpClient(); // редиректы не следуем — проверяем их сами
            String base = "http://127.0.0.1:" + app.port();

            HttpResponse<String> home = http.send(HttpRequest.newBuilder(URI.create(base + "/draft?opponent=UPPO_TEAM")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(302, home.statusCode());
            assertTrue(home.headers().firstValue("Location").orElse("").startsWith("/login?next="));

            assertEquals(200, http.send(HttpRequest.newBuilder(URI.create(base + "/health")).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode(), "/health — без входа");
            assertEquals(200, http.send(HttpRequest.newBuilder(URI.create(base + "/static/app.css")).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode(), "статика — без входа (страница входа со стилями)");

            HttpResponse<String> api = http.send(HttpRequest.newBuilder(URI.create(base + "/api/draft"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"opponent\":\"UPPO_TEAM\",\"weFirst\":true,\"heroes\":[]}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, api.statusCode());

            assertEquals(401, post(http, base, "wrong", "/").statusCode());

            HttpResponse<String> ok = post(http, base, "secret", "/draft?opponent=UPPO_TEAM");
            assertEquals(302, ok.statusCode());
            assertEquals("/draft?opponent=UPPO_TEAM", ok.headers().firstValue("Location").orElseThrow());
            String cookie = ok.headers().firstValue("Set-Cookie").orElseThrow();
            assertTrue(cookie.contains("HttpOnly"));
            String session = cookie.split(";")[0];

            HttpResponse<String> after = http.send(HttpRequest.newBuilder(URI.create(base + "/draft?opponent=UPPO_TEAM"))
                    .header("Cookie", session).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, after.statusCode());
            assertTrue(after.body().contains("Выйти"));
        } finally {
            app.stop();
        }
    }

    private static HttpResponse<String> post(HttpClient http, String base, String password, String next) throws Exception {
        String form = "password=" + URLEncoder.encode(password, StandardCharsets.UTF_8)
                + "&next=" + URLEncoder.encode(next, StandardCharsets.UTF_8);
        return http.send(HttpRequest.newBuilder(URI.create(base + "/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
