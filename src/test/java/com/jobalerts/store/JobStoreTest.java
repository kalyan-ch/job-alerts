package com.jobalerts.store;

import com.jobalerts.TestDb;
import com.jobalerts.domain.Job;
import com.jobalerts.domain.Scored;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@Import(TestDb.class)
class JobStoreTest {

    @Autowired JobStore store;

    @Test
    void syncSoftDeletesMissingKeepsFailedBoardsAndRevivesReposts() {
        var a = job("gh:co:1", "desc"); var b = job("gh:co:2", "desc"); var other = job("lv:x:9", "has\0nul");

        store.sync("greenhouse/co", List.of(a, b));
        store.sync("lever/x", List.of(other));
        store.sync("greenhouse/co", List.of(a));                  // b gone; lever fetch failed so never synced
        assertEquals(Set.of("gh:co:1", "lv:x:9"), ids());

        store.sync("greenhouse/co", List.of(a, b));               // b reposted
        assertEquals(Set.of("gh:co:1", "gh:co:2", "lv:x:9"), ids());
        assertEquals(a, store.unscored().stream().filter(j -> j.id().equals(a.id())).findFirst().orElseThrow());
    }

    @Test
    void scoredJobIsNeverRescoredAndSentOnce() {
        var c = job("gh:s:1", "d"); var low = job("gh:s:2", "d");
        store.sync("greenhouse/s", List.of(c, low));
        store.saveScores(List.of(new Scored(c, 90, "fit", "yes"), new Scored(low, 20, "no", "unknown")));
        store.sync("greenhouse/s", List.of(c, low));              // next day's fetch keeps the score

        assertEquals(Set.of(), store.unscored().stream().map(Job::id).filter(id -> id.startsWith("gh:s:")).collect(Collectors.toSet()));
        var top = store.unsent(70, 10);
        assertEquals(List.of(new Scored(c, 90, "fit", "yes")), top);
        store.markNotified(top);
        assertEquals(List.of(), store.unsent(70, 10));

        var old = new Job("gh:s:3", "co", "Software Engineer", "SF", "https://x", "d", Instant.now().minus(Duration.ofDays(2)));
        store.sync("greenhouse/s", List.of(c, low, old));
        store.saveScores(List.of(new Scored(old, 95, "fit", "yes")));
        assertEquals(List.of(), store.unsent(70, 10), "posted over a day ago");

        var dash = store.latest(100).stream().map(JobStore.Top::id).filter(id -> id.startsWith("gh:s:")).toList();
        assertEquals("gh:s:3", dash.getLast(), "dashboard: newest first, scored or not");
    }

    private Set<String> ids() { return store.unscored().stream().map(Job::id).collect(Collectors.toSet()); }

    private static Job job(String id, String desc) {
        return new Job(id, "co", "Software Engineer", "SF", "https://x", desc, Instant.now().truncatedTo(ChronoUnit.MILLIS));
    }
}
