package com.jobalerts.ats;

import com.jobalerts.domain.Job;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class AshbyBoard implements Board {
    private final Http http;

    AshbyBoard(Http http) { this.http = http; }

    @Override public String name() { return "ashby"; }

    @Override
    public List<Job> fetch(String slug) {
        var body = http.get("https://api.ashbyhq.com/posting-api/job-board/" + slug + "?includeCompensation=true");
        var jobs = new ArrayList<Job>();
        for (var j : body.path("jobs")) {
            jobs.add(new Job(
                    "ab:" + slug + ":" + j.path("id").asString(),
                    slug,
                    j.path("title").asString(""),
                    j.path("location").asString(""),
                    j.path("jobUrl").asString(""),
                    j.path("descriptionPlain").asString("") + "\n" + j.path("compensation").toString(),
                    Http.instant(j.path("publishedAt").asString(""))));
        }
        return jobs;
    }
}
