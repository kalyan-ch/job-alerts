package com.jobalerts.ats;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.Instant;

/** Shared JSON GET + the text cleanup every board needs. */
@Component
public class Http {
    private final RestClient client = RestClient.create();

    public JsonNode get(String url) {
        return client.get().uri(url).retrieve().body(JsonNode.class);
    }

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
