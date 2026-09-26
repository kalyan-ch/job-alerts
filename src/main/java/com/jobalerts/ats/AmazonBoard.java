package com.jobalerts.ats;

import com.jobalerts.domain.Job;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * amazon.jobs — its own site, but the search page is backed by a public JSON endpoint.
 *
 * <p>Slug is that endpoint's query string; the board adds paging, e.g.
 * {@code base_query=software engineer&normalized_state_name[]=California}.
 */
@Component
public class AmazonBoard implements Board {
    private static final int PAGE = 100;
    private static final int MAX_JOBS = 1000;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US);

    private final Http http;

    AmazonBoard(Http http) { this.http = http; }

    @Override public String name() { return "amazon"; }

    @Override
    public List<Job> fetch(String slug) {
        var jobs = new ArrayList<Job>();
        for (int offset = 0; offset < MAX_JOBS; offset += PAGE) {
            var page = http.get("https://www.amazon.jobs/en/search.json?" + slug
                    + "&result_limit=" + PAGE + "&offset=" + offset).path("jobs");
            for (var j : page) {
                jobs.add(new Job(
                        "amz:" + j.path("id_icims").asString(),
                        "amazon",
                        j.path("title").asString(""),
                        j.path("normalized_location").asString(""),
                        "https://www.amazon.jobs" + j.path("job_path").asString(""),
                        Http.plain(j.path("description").asString("") + " "
                                + j.path("basic_qualifications").asString("") + " "
                                + j.path("preferred_qualifications").asString("")),
                        date(j.path("posted_date").asString(""))));
            }
            if (page.size() < PAGE) break;
        }
        return jobs;
    }

    /** "April  9, 2026" — note the double space before single-digit days. */
    private static Instant date(String s) {
        try {
            return LocalDate.parse(s.replaceAll("\\s+", " "), DATE).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (Exception e) {
            return null;
        }
    }
}
