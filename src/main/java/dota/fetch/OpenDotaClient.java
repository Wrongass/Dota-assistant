package dota.fetch;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * GET-клиент OpenDota: пауза между запросами, повтор на 429/5xx с нарастающей паузой,
 * лог остатка лимита из заголовков X-Rate-Limit-Remaining-*.
 */
public class OpenDotaClient {

    public static final String BASE_URL = "https://api.opendota.com/api";
    static final int MAX_RETRIES = 5;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final String baseUrl;
    private final String apiKey;
    private final Duration minInterval;
    private long lastRequestNanos;

    public OpenDotaClient(String baseUrl, String apiKey, Duration minInterval) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.minInterval = minInterval;
    }

    /** @param pathAndQuery например {@code /players/1/matches?significant=0} */
    public byte[] get(String pathAndQuery) throws IOException, InterruptedException {
        URI uri = URI.create(baseUrl + withApiKey(pathAndQuery));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(3))
                .header("Accept", "application/json")
                .GET()
                .build();

        for (int attempt = 0; ; attempt++) {
            throttle();
            HttpResponse<byte[]> response;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            } catch (IOException e) {
                if (attempt >= MAX_RETRIES) {
                    throw e;
                }
                Duration wait = backoff(attempt, null);
                Log.error("%s: %s, retry in %ds", pathAndQuery, e, wait.toSeconds());
                Thread.sleep(wait);
                continue;
            }

            int status = response.statusCode();
            logRateLimit(pathAndQuery, status, response);
            if (status == 200) {
                return response.body();
            }
            boolean retryable = status == 429 || status >= 500;
            if (!retryable || attempt >= MAX_RETRIES) {
                throw new IOException("HTTP " + status + " for " + pathAndQuery + ": " + snippet(response.body()));
            }
            Duration wait = backoff(attempt, response.headers().firstValue("Retry-After").orElse(null));
            Log.info("HTTP %d, retry %d/%d in %ds", status, attempt + 1, MAX_RETRIES, wait.toSeconds());
            Thread.sleep(wait);
        }
    }

    /** Пауза 2, 4, 8, 16, 32 с; если сервер прислал Retry-After больше — ждём его. */
    static Duration backoff(int attempt, String retryAfter) {
        Duration exponential = Duration.ofSeconds(2L << attempt);
        Optional<Duration> server = parseSeconds(retryAfter);
        return server.filter(s -> s.compareTo(exponential) > 0).orElse(exponential);
    }

    private static Optional<Duration> parseSeconds(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Duration.ofSeconds(Long.parseLong(value.trim())));
        } catch (NumberFormatException e) {
            return Optional.empty(); // HTTP-date формат не разбираем
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

    private void logRateLimit(String path, int status, HttpResponse<?> response) throws InterruptedException {
        String minute = response.headers().firstValue("X-Rate-Limit-Remaining-Minute").orElse("?");
        String day = response.headers().firstValue("X-Rate-Limit-Remaining-Day").orElse("?");
        Log.info("GET %s -> %d, rate limit left: minute=%s, day=%s", path, status, minute, day);
        if ("0".equals(minute)) {
            Log.info("Minute rate limit exhausted, sleeping 60s");
            Thread.sleep(Duration.ofSeconds(60));
        }
    }

    private String withApiKey(String pathAndQuery) {
        if (apiKey == null || apiKey.isBlank()) {
            return pathAndQuery;
        }
        char sep = pathAndQuery.contains("?") ? '&' : '?';
        return pathAndQuery + sep + "api_key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
    }

    private static String snippet(byte[] body) {
        String s = new String(body, StandardCharsets.UTF_8);
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
