package com.jobalerts;

import com.jobalerts.config.Props;
import com.jobalerts.domain.Job;

import java.nio.file.Path;
import java.util.List;

/** Shared test data so each layer's test stays about that layer. */
public final class Fixtures {

    public static final Props PROPS = new Props(
            Path.of("seen.txt"), Path.of("resume.txt"), 70, 180_000, 10,
            "San Francisco|Bay Area|Remote.*US", "Remote \\(non-US\\)",
            "no sponsorship|security clearance",
            List.of("engineer"), List.of("intern", "manager"),
            "http://localhost:11434", "test-model", "");

    public static Job job(String title, String location, String description) {
        return new Job("id", "co", title, location, "https://example.com/apply", description, null);
    }

    private Fixtures() {}
}
