package com.jobalerts.ats;

import com.jobalerts.domain.Job;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Workday CXS — the only board most big tech runs (NVIDIA, Adobe, Salesforce, PayPal, eBay).
 *
 * <p>Unlike the other three this needs two calls per job: the list endpoint returns no description
 * and only "Posted Today"-style dates, so each posting is fetched individually. Tenants hold
 * thousands of jobs, so the slug carries a server-side search term to keep that bounded.
 *
 * <p>Slug format: {@code tenant/wdN/site/searchText}, e.g. {@code nvidia/wd5/NVIDIAExternalCareerSite/backend engineer}.
 */
@Component
public class WorkdayBoard implements Board {
    private static final int PAGE = 20;      // the API silently returns nothing above this
    private static final int MAX_JOBS = 60;  // 60 jobs = 63 requests; the prefilter drops most anyway

    private final RestClient client = RestClient.create();

    @Override public String name() { return "workday"; }

    @Override
    public List<Job> fetch(String slug) {
        var parts = slug.split("/", 4);
        if (parts.length != 4) throw new IllegalArgumentException(
                "workday slug must be tenant/wdN/site/searchText, got: " + slug);
        var tenant = parts[0];
        var base = "https://" + tenant + "." + parts[1] + ".myworkdayjobs.com/wday/cxs/" + tenant + "/" + parts[2];

        var jobs = new ArrayList<Job>();
        for (int offset = 0; offset < MAX_JOBS; offset += PAGE) {
            var page = post(base + "/jobs", Map.of("limit", PAGE, "offset", offset, "searchText", parts[3]))
                    .path("jobPostings");
            for (var p : page) jobs.add(detail(base, tenant, p.path("externalPath").asString("")));
            if (page.size() < PAGE) break;
        }
        return jobs;
    }

    /** The list endpoint gives title and path only — description and real date live here.
     *  {@code externalPath} already starts with "/job"; appending another gives a 422. */
    private Job detail(String base, String tenant, String path) {
        var info = client.get().uri(base + path).retrieve().body(JsonNode.class).path("jobPostingInfo");
        return new Job(
                "wd:" + tenant + ":" + info.path("jobReqId").asString(),
                tenant,
                info.path("title").asString(""),
                info.path("location").asString(""),
                info.path("externalUrl").asString(""),
                Http.plain(info.path("jobDescription").asString("")),
                Http.instant(info.path("startDate").asString("") + "T00:00:00Z"));
    }

    private JsonNode post(String url, Map<String, Object> body) {
        return client.post().uri(url).body(body).retrieve().body(JsonNode.class);
    }
}
