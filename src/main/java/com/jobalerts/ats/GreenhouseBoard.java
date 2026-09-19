package com.jobalerts.ats;

import com.jobalerts.domain.Job;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class GreenhouseBoard implements Board {
    private final Http http;

    GreenhouseBoard(Http http) { this.http = http; }

    @Override public String name() { return "greenhouse"; }

    @Override
    public List<Job> fetch(String slug) {
        var body = http.get("https://boards-api.greenhouse.io/v1/boards/" + slug + "/jobs?content=true");
        var jobs = new ArrayList<Job>();
        for (var j : body.path("jobs")) {
            jobs.add(new Job(
                    "gh:" + slug + ":" + j.path("id").asString(),
                    slug,
                    j.path("title").asString(""),
                    j.path("location").path("name").asString(""),
                    j.path("absolute_url").asString(""),
                    Http.plain(j.path("content").asString("")),
                    Http.instant(j.path("updated_at").asString(""))));
        }
        return jobs;
    }
}
