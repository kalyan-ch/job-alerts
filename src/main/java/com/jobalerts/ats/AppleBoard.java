package com.jobalerts.ats;

import com.jobalerts.domain.Job;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Apple jobs — no API, so this scrapes the search page. Results are embedded as
 * {@code __staticRouterHydrationData = JSON.parse("...")}: JSON inside a JS string, decoded twice.
 *
 * <p>The keyword parameter is ignored server-side, so the slug narrows by location and team, e.g.
 * {@code location=san-francisco-bay-area-SFMETRO&team=apps-and-frameworks-SFTWR-AF}.
 * The list carries only a summary, not the full posting.
 */
@Component
public class AppleBoard implements Board {
    private static final String BASE = "https://jobs.apple.com/en-us/";
    private static final Pattern DATA = Pattern.compile("__staticRouterHydrationData = JSON\\.parse\\((\".*?\")\\);", Pattern.DOTALL);
    private static final int PAGE = 20;
    private static final int MAX_PAGES = 50;

    private final Http http;

    AppleBoard(Http http) { this.http = http; }

    @Override public String name() { return "apple"; }

    @Override
    public List<Job> fetch(String slug) {
        var jobs = new ArrayList<Job>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            var search = search(http.text(BASE + "search?" + slug + "&page=" + page));
            var rows = search.path("searchResults");
            for (var r : rows) jobs.add(job(r));
            if (rows.size() < PAGE || jobs.size() >= search.path("totalRecords").asInt()) break;
        }
        return jobs;
    }

    static JsonNode search(String html) {
        var m = DATA.matcher(html);
        if (!m.find()) throw new IllegalStateException("no hydration data, page layout changed");
        return Http.json(Http.json(m.group(1)).asString()).path("loaderData").path("search");
    }

    static Job job(JsonNode r) {
        var locs = new ArrayList<String>();
        for (var l : r.path("locations")) locs.add(l.path("name").asString(""));
        return new Job(
                "ap:" + r.path("id").asString(),
                "apple",
                r.path("postingTitle").asString(""),
                String.join("; ", locs),
                BASE + "details/" + r.path("id").asString() + "/" + r.path("transformedPostingTitle").asString(""),
                r.path("jobSummary").asString(""),
                Http.instant(r.path("postDateInGMT").asString("")));
    }
}
