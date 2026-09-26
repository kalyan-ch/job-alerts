package com.jobalerts.store;

import com.jobalerts.domain.Job;
import com.jobalerts.domain.Scored;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScoreCacheTest {

    @Test
    void scoresSurviveARestart(@TempDir Path dir) throws Exception {
        var file = dir.resolve("scores.jsonl");
        var job = new Job("gh:1", "co", "Software Engineer", "SF", "https://x", "desc", Instant.parse("2026-09-01T00:00:00Z"));
        new ScoreCache(file).add(List.of(new Scored(job, 82, "good fit", false)));

        var reloaded = new ScoreCache(file);
        assertEquals(new Scored(job, 82, "good fit", false), reloaded.get("gh:1"));
        assertEquals(1, reloaded.all().size());
    }
}
