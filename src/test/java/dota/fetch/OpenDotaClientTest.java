package dota.fetch;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenDotaClientTest {

    @Test
    void backoffGrowsExponentially() {
        assertEquals(Duration.ofSeconds(2), OpenDotaClient.backoff(0, null));
        assertEquals(Duration.ofSeconds(4), OpenDotaClient.backoff(1, null));
        assertEquals(Duration.ofSeconds(32), OpenDotaClient.backoff(4, null));
    }

    @Test
    void backoffRespectsLongerRetryAfter() {
        assertEquals(Duration.ofSeconds(30), OpenDotaClient.backoff(0, "30"));
        assertEquals(Duration.ofSeconds(8), OpenDotaClient.backoff(2, "1"));
        assertEquals(Duration.ofSeconds(2), OpenDotaClient.backoff(0, "Wed, 21 Oct 2015 07:28:00 GMT"));
    }
}
