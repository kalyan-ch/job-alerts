package com.jobalerts.ats;

import com.jobalerts.domain.Job;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Google careers — no API, so this scrapes the search page. Results sit in an inline
 * {@code AF_initDataCallback({key: 'ds:1', ... data:[jobs, _, total, pageSize]})} block,
 * each job a positional array.
 *
 * <p>Slug is the search query string, e.g. {@code q=software engineer&location=California, USA}.
 */
@Component
public class GoogleBoard implements Board {
    private static final String BASE = "https://www.google.com/about/careers/applications/jobs/results/";
    private static final Pattern DATA = Pattern.compile("key: 'ds:1', hash: '\\d+', data:(.*?), sideChannel", Pattern.DOTALL);
    private static final int PAGE = 20;
    private static final int MAX_PAGES = 50;

    private final Http http;

    GoogleBoard(Http http) { this.http = http; }

    @Override public String name() { return "google"; }

    @Override
    public List<Job> fetch(String slug) {
        var jobs = new ArrayList<Job>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            var data = data(http.text(BASE + "?" + slug + "&page=" + page));
            for (var r : data.path(0)) jobs.add(job(r));
            if (data.path(0).size() < PAGE || jobs.size() >= data.path(2).asInt()) break;
        }
        return jobs;
    }

    // ponytail: positional indexes read off the live page 2026-09-26. A redesign throws here
    // (shows as "google/... fetch failed"); re-derive the indexes from the page source then.
    static JsonNode data(String html) {
        var m = DATA.matcher(html);
        if (!m.find()) throw new IllegalStateException("no ds:1 data block, page layout changed");
        return Http.json(m.group(1));
    }

    static Job job(JsonNode r) {
        var locs = new ArrayList<String>();
        for (var l : r.path(9)) locs.add(l.path(0).asString(""));
        var created = r.path(12).path(0);
        return new Job(
                "gg:" + r.path(0).asString(),
                "google",
                r.path(1).asString(""),
                String.join("; ", locs),
                BASE + r.path(0).asString(),
                Http.plain(r.path(10).path(1).asString("") + " " + r.path(3).path(1).asString("")
                        + " " + r.path(4).path(1).asString("")),
                created.isNumber() ? Instant.ofEpochSecond(created.asLong()) : null);
    }
}
