package dota.fetch;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * POST-клиент STRATZ GraphQL: токен Bearer, пауза между запросами, повтор на 429/5xx с нарастающей паузой,
 * лог остатка лимита из заголовков X-RateLimit-Remaining-*. Лимиты бесплатного токена: 8 в секунду, 150 в минуту,
 * 1500 в час, 15 000 в день.
 */
public class StratzClient {

    public static final String URL = "https://api.stratz.com/graphql";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();
    private final String url;
    private final String token;
    private final Duration minInterval;
    private long lastRequestNanos;

    public StratzClient(String url, String token, Duration minInterval) {
        this.url = url;
        this.token = token;
        this.minInterval = minInterval;
    }

    /** @param label короткое имя запроса для лога */
    public byte[] query(String label, String graphql) throws IOException, InterruptedException {
        byte[] body = JSON.writeValueAsBytes(Map.of("query", graphql));
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + token)
                // без User-Agent STRATZ отвечает 403 на запросы не из браузера
                .header("User-Agent", "STRATZ_API")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        for (int attempt = 0; ; attempt++) {
            throttle();
            HttpResponse<byte[]> response;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            } catch (IOException e) {
                if (attempt >= OpenDotaClient.MAX_RETRIES) {
                    throw e;
                }
                Duration wait = OpenDotaClient.backoff(attempt, null);
                Log.error("%s: %s, retry in %ds", label, e, wait.toSeconds());
                Thread.sleep(wait);
                continue;
            }

            int status = response.statusCode();
            logRateLimit(label, status, response);
            if (status == 200) {
                return response.body();
            }
            boolean retryable = status == 429 || status >= 500;
            if (!retryable || attempt >= OpenDotaClient.MAX_RETRIES) {
                throw new IOException("HTTP " + status + " for " + label + ": " + snippet(response.body()));
            }
            Duration wait = OpenDotaClient.backoff(attempt, response.headers().firstValue("Retry-After").orElse(null));
            Log.info("HTTP %d, retry %d/%d in %ds", status, attempt + 1, OpenDotaClient.MAX_RETRIES, wait.toSeconds());
            Thread.sleep(wait);
        }
    }

    private void throttle() throws InterruptedException {
        long elapsed = System.nanoTime() - lastRequestNanos;
        long waitNanos = minInterval.toNanos() - elapsed;
        if (lastRequestNanos != 0 && waitNanos > 0) {
            Thread.sleep(Duration.ofNanos(waitNanos));
        }
        lastRequestNanos = System.nanoTime();
    }

    private void logRateLimit(String label, int status, HttpResponse<?> response) throws InterruptedException {
        String minute = response.headers().firstValue("X-RateLimit-Remaining-Minute").orElse("?");
        String hour = response.headers().firstValue("X-RateLimit-Remaining-Hour").orElse("?");
        String day = response.headers().firstValue("X-RateLimit-Remaining-Day").orElse("?");
        Log.info("STRATZ %s -> %d, rate limit left: minute=%s, hour=%s, day=%s", label, status, minute, hour, day);
        if ("0".equals(minute)) {
            Log.info("Minute rate limit exhausted, sleeping 60s");
            Thread.sleep(Duration.ofSeconds(60));
        }
        if ("0".equals(hour)) {
            throw new IllegalStateException("STRATZ hourly rate limit exhausted, try again in an hour");
        }
    }

    private static String snippet(byte[] body) {
        String s = new String(body, StandardCharsets.UTF_8);
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
