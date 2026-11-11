package com.jobalerts.ats;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/** Shared JSON GET + the text cleanup every board needs. */
@Component
public class Http {
    private static final JsonMapper JSON = new JsonMapper();
    // Career sites serve scrapers a different (or empty) page without a browser UA.
    // Timeouts: one stalled board otherwise blocks the whole run, since AtsClient waits for every fetch.
    private final RestClient client = RestClient.builder()
            .requestFactory(timeouts())
            .defaultHeader("User-Agent", "Mozilla/5.0").build();

    private static JdkClientHttpRequestFactory timeouts() {
        var f = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        f.setReadTimeout(Duration.ofSeconds(30));
        return f;
    }

    public JsonNode get(String url) {
        return retry(() -> client.get().uri(url).retrieve().body(JsonNode.class), BACKOFF);
    }

    public JsonNode post(String url, Object body) {
        return retry(() -> client.post().uri(url).body(body).retrieve().body(JsonNode.class), BACKOFF);
    }

    /** Raw page body, for the boards that scrape HTML. */
    public String text(String url) {
        return retry(() -> client.get().uri(url).retrieve().body(String.class), BACKOFF);
    }

    public static final Duration BACKOFF = Duration.ofSeconds(10);

    /** On 429 wait base, 2x, 4x and retry; sites throttle bursts, not steady traffic. */
    public static <T> T retry(Supplier<T> call, Duration base) {
        for (int i = 0; ; i++) {
            try {
                return call.get();
            } catch (HttpClientErrorException.TooManyRequests e) {
                if (i == 3) throw e;
                pause(base.multipliedBy(1L << i));
            }
        }
    }

    public static void pause(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    public static JsonNode json(String s) { return JSON.readTree(s); }

    /** Greenhouse returns HTML-escaped markup; strip to readable text for the regex prefilter. */
    public static String plain(String html) {
        return html.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&nbsp;", " ").replace("&amp;", "&")
                .replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        // ponytail: six entities cover ATS markup. Swap in a real unescaper if postings show &#8212;-style numerics.
    }

    public static Instant instant(String iso) {
        try { return Instant.parse(iso); } catch (Exception e) { return null; }
    }
}
