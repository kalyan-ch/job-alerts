package com.jobalerts.ats;

import com.jobalerts.domain.Job;
import com.jobalerts.filter.Prefilter;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Netflix careers (Eightfold) — public JSON, but like Workday the list has no description,
 * so each posting costs a second call. The list caps pages at 10 whatever you ask for.
 *
 * <p>Slug is the list query string, e.g. {@code query=software engineer}.
 */
@Component
public class NetflixBoard implements Board {
    private static final String BASE = "https://explore.jobs.netflix.net/api/apply/v2/jobs";
    private static final int PAGE = 10;
    private static final int MAX_JOBS = 300;
    private static final Duration GAP = Duration.ofMillis(500);

    private final Http http;
    private final Prefilter filter;

    NetflixBoard(Http http, Prefilter filter) { this.http = http; this.filter = filter; }

    @Override public String name() { return "netflix"; }

    @Override
    public List<Job> fetch(String slug) {
        var jobs = new ArrayList<Job>();
        for (int start = 0; start < MAX_JOBS; start += PAGE) {
            var page = http.get(BASE + "?domain=netflix.com&" + slug + "&start=" + start + "&num=" + PAGE)
                    .path("positions");
            for (var p : page) {
                if (!filter.listingOk(p.path("name").asString(""), p.path("location").asString(""))) continue;
                Http.pause(GAP);   // ~170 detail calls back to back trips their 429
                var d = http.get(BASE + "/" + p.path("id").asString() + "?domain=netflix.com");
                jobs.add(new Job(
                        "nf:" + d.path("id").asString(),
                        "netflix",
                        d.path("name").asString(""),
                        d.path("location").asString(""),
                        d.path("canonicalPositionUrl").asString(""),
                        Http.plain(d.path("job_description").asString("")),
                        d.has("t_create") ? Instant.ofEpochSecond(d.path("t_create").asLong()) : null));
            }
            if (page.size() < PAGE) break;
        }
        return jobs;
    }
}
