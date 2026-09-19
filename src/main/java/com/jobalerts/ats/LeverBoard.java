package com.jobalerts.ats;

import com.jobalerts.domain.Job;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class LeverBoard implements Board {
    private final Http http;

    LeverBoard(Http http) { this.http = http; }

    @Override public String name() { return "lever"; }

    @Override
    public List<Job> fetch(String slug) {
        var body = http.get("https://api.lever.co/v0/postings/" + slug + "?mode=json");
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
}
