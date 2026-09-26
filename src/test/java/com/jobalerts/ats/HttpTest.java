package com.jobalerts.ats;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpTest {

    @Test
    void stripsGreenhouseHtml() {
        assertEquals("Hello R&D world", Http.plain("&lt;p&gt;Hello  R&amp;D\nworld&lt;/p&gt;"));
    }

    @Test
    void retriesOn429ThenGivesUp() {
        var calls = new AtomicInteger();
        assertEquals("ok", Http.retry(() -> {
            if (calls.incrementAndGet() < 3) throw tooMany();
            return "ok";
        }, Duration.ofMillis(1)));
        assertEquals(3, calls.get());

        assertThrows(HttpClientErrorException.TooManyRequests.class,
                () -> Http.retry(() -> { throw tooMany(); }, Duration.ofMillis(1)));
    }

    private static HttpClientErrorException tooMany() {
        return HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "slow down", null, null, null);
    }

    @Test
    void badTimestampYieldsNullNotThrow() {
        assertNull(Http.instant(""));
        assertNull(Http.instant("not a date"));
    }
}
