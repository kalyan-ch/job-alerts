package com.jobalerts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class AtsClient {
    private static final Logger log = LoggerFactory.getLogger(AtsClient.class);

    private final RestClient http = RestClient.create();
    private final Boards boards;

    AtsClient(Boards boards) { this.boards = boards; }

    public List<Job> fetchAll() {
        var out = new ArrayList<Job>();
        for (var c : boards.companies()) {
            try {
                out.addAll(switch (c.board()) {
                    case "greenhouse" -> greenhouse(c.slug());
                    case "lever" -> lever(c.slug());
                    case "ashby" -> ashby(c.slug());
                    default -> List.<Job>of();
                });
            } catch (Exception e) {
                log.warn("{}/{} fetch failed: {}", c.board(), c.slug(), e.toString());
            }
        }
        log.info("fetched {} jobs from {} boards", out.size(), boards.companies().size());
        return out;
    }

    private List<Job> greenhouse(String slug) {
        var body = get("https://boards-api.greenhouse.io/v1/boards/" + slug + "/jobs?content=true");
        var jobs = new ArrayList<Job>();
        for (var j : body.path("jobs")) {
            jobs.add(new Job(
                    "gh:" + slug + ":" + j.path("id").asString(),
                    slug,
                    j.path("title").asString(""),
                    j.path("location").path("name").asString(""),
                    j.path("absolute_url").asString(""),
                    plain(j.path("content").asString("")),
                    instant(j.path("updated_at").asString(""))));
        }
        return jobs;
    }

    private List<Job> lever(String slug) {
        var body = get("https://api.lever.co/v0/postings/" + slug + "?mode=json");
        var jobs = new ArrayList<Job>();
        for (var j : body) {
            jobs.add(new Job(
                    "lv:" + slug + ":" + j.path("id").asString(),
                    slug,
                    j.path("text").asString(""),
                    j.path("categories").path("location").asString(""),
                    j.path("hostedUrl").asString(""),
                    j.path("descriptionPlain").asString("") + "\n"
                            + j.path("additionalPlain").asString("") + "\n"
                            + j.path("salaryDescriptionPlain").asString(""),
                    j.has("createdAt") ? Instant.ofEpochMilli(j.path("createdAt").asLong()) : null));
        }
        return jobs;
    }

    private List<Job> ashby(String slug) {
        var body = get("https://api.ashbyhq.com/posting-api/job-board/" + slug + "?includeCompensation=true");
        var jobs = new ArrayList<Job>();
        for (var j : body.path("jobs")) {
            jobs.add(new Job(
                    "ab:" + slug + ":" + j.path("id").asString(),
                    slug,
                    j.path("title").asString(""),
                    j.path("location").asString(""),
                    j.path("jobUrl").asString(""),
                    j.path("descriptionPlain").asString("") + "\n" + j.path("compensation").toString(),
                    instant(j.path("publishedAt").asString(""))));
        }
        return jobs;
    }

    private JsonNode get(String url) {
        return http.get().uri(url).retrieve().body(JsonNode.class);
    }

    /** Greenhouse returns HTML-escaped markup; strip to readable text for the regex prefilter. */
    static String plain(String html) {
        return html.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&nbsp;", " ").replace("&amp;", "&")
                .replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        // ponytail: six entities cover ATS markup. Swap in a real unescaper if postings show &#8212;-style numerics.
    }

    private static Instant instant(String iso) {
        try { return Instant.parse(iso); } catch (Exception e) { return null; }
    }
}
