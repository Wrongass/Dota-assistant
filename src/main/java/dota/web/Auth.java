package dota.web;

import io.javalin.http.Context;
import io.javalin.http.Cookie;
import io.javalin.http.SameSite;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Вход по общему паролю команды (переменная окружения SITE_PASSWORD). Если пароль не задан — вход не нужен
 * (локальный запуск). После входа — подписанная cookie на 30 дней: «срок.HMAC(срок)». Ключ HMAC выводится из
 * пароля, поэтому смена пароля разлогинивает всех. Не больше 5 неудачных попыток в минуту с одного адреса.
 */
final class Auth {

    static final String COOKIE = "dota_session";
    static final Duration SESSION = Duration.ofDays(30);
    private static final int MAX_FAILURES_PER_MINUTE = 5;

    private final byte[] passwordHash;
    private final byte[] key;
    private final Clock clock;
    private final Map<String, Deque<Long>> failures = new ConcurrentHashMap<>();

    Auth(String password, Clock clock) {
        this.clock = clock;
        if (password == null || password.isBlank()) {
            this.passwordHash = null;
            this.key = null;
        } else {
            this.passwordHash = sha256("password:" + password);
            this.key = sha256("session-key:" + password);
        }
    }

    static Auth fromEnvironment() {
        return new Auth(System.getenv("SITE_PASSWORD"), Clock.systemUTC());
    }

    boolean enabled() {
        return passwordHash != null;
    }

    boolean checkPassword(String candidate) {
        return candidate != null && MessageDigest.isEqual(passwordHash, sha256("password:" + candidate));
    }

    /** Значение cookie: срок действия (epoch-секунды) и подпись. */
    String newToken() {
        long expires = clock.instant().plus(SESSION).getEpochSecond();
        return expires + "." + sign(String.valueOf(expires));
    }

    boolean validToken(String token) {
        if (!enabled()) {
            return true;
        }
        if (token == null) {
            return false;
        }
        int dot = token.indexOf('.');
        if (dot <= 0) {
            return false;
        }
        String expires = token.substring(0, dot);
        byte[] given = token.substring(dot + 1).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(given, sign(expires).getBytes(StandardCharsets.UTF_8))) {
            return false;
        }
        try {
            return Long.parseLong(expires) > clock.instant().getEpochSecond();
        } catch (NumberFormatException e) {
            return false;
        }
    }

    boolean loggedIn(Context ctx) {
        return validToken(ctx.cookie(COOKIE));
    }

    void startSession(Context ctx) {
        Cookie cookie = new Cookie(COOKIE, newToken());
        cookie.setMaxAge((int) SESSION.toSeconds());
        cookie.setHttpOnly(true);
        cookie.setSecure(isHttps(ctx));
        cookie.setSameSite(SameSite.LAX);
        cookie.setPath("/");
        ctx.cookie(cookie);
    }

    void endSession(Context ctx) {
        ctx.removeCookie(COOKIE, "/");
    }

    /** За прокси (Caddy/nginx) схему подсказывает X-Forwarded-Proto. */
    static boolean isHttps(Context ctx) {
        return "https".equalsIgnoreCase(ctx.header("X-Forwarded-Proto")) || "https".equalsIgnoreCase(ctx.scheme());
    }

    /** true — адрес исчерпал лимит неудачных попыток за последнюю минуту. */
    boolean tooManyFailures(String ip) {
        Deque<Long> times = failures.get(ip);
        if (times == null) {
            return false;
        }
        long minuteAgo = clock.millis() - 60_000;
        synchronized (times) {
            while (!times.isEmpty() && times.peekFirst() < minuteAgo) {
                times.pollFirst();
            }
            return times.size() >= MAX_FAILURES_PER_MINUTE;
        }
    }

    void recordFailure(String ip) {
        Deque<Long> times = failures.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (times) {
            times.addLast(clock.millis());
        }
    }

    /** Куда вернуть после входа: только локальный путь, без «//» (иначе открытый редирект на чужой сайт). */
    static String safeNext(String next) {
        if (next == null || !next.startsWith("/") || next.startsWith("//") || next.startsWith("/\\")) {
            return "/";
        }
        return next;
    }

    private String sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 недоступен", e);
        }
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
