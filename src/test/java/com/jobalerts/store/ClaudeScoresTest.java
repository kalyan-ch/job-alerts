package com.jobalerts.store;

import com.jobalerts.TestDb;
import com.jobalerts.config.Claude;
import com.jobalerts.domain.Job;
import com.jobalerts.domain.Scored;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Import(TestDb.class)
class ClaudeScoresTest {

    @Autowired JobStore store;
    @Autowired Claude claude;

    @Test
    void onlyLocallyScoredJobsQueueForClaudeAndCompareShowsTheGap() {
        var a = new Job("cmp-a", "co", "Senior Engineer", "SF", "u", "d", null);
        var b = new Job("cmp-b", "co", "Staff Engineer", "SF", "u", "d", null);
        store.sync("test/cmp", List.of(a, b));
        try {
            check(a);
        } finally {
            store.sync("test/cmp", List.of());   // soft-delete: other tests count every live job
        }
    }

    private void check(Job a) {
        store.saveScores(List.of(new Scored(a, 80, "local", "unknown")));   // b stays unscored locally

        assertEquals(Set.of("cmp-a", "cmp-b"), queued("m"));   // Claude doesn't wait for a local score

        store.saveClaudeScore("m", new Scored(a, 55, "claude", "no"));
        assertEquals(Set.of("cmp-b"), queued("m"));
        assertEquals(Set.of("cmp-a", "cmp-b"), queued("other-model"));   // per model

        var row = store.compare("m", 10).stream().filter(r -> r.id().equals("cmp-a")).findFirst().orElseThrow();
        assertEquals(-25, row.gap());

        // Cards show the configured model's score beside the local one.
        store.saveClaudeScore(claude.model(), new Scored(a, 60, "fits", "no"));
        var card = store.latest(1000).stream().filter(t -> t.id().equals("cmp-a")).findFirst().orElseThrow();
        assertEquals(80, card.score());
        assertEquals(60, card.claudeScore());
        assertTrue(store.topByClaude(1000).stream().anyMatch(t -> t.id().equals("cmp-a")), "ranked by Claude");

        // Each reset clears only its own side; reset-claude only its own model.
        store.resetClaudeScores("m");
        assertEquals(Set.of("cmp-a", "cmp-b"), queued("m"));
        assertEquals(60, card().claudeScore());
        store.resetLocalScores();
        assertEquals(60, card().claudeScore());
        assertEquals(null, card().score());
    }

    private JobStore.Top card() {
        return store.latest(1000).stream().filter(t -> t.id().equals("cmp-a")).findFirst().orElseThrow();
    }

    /** Other tests share this database, so look only at this test's jobs. */
    private Set<String> queued(String model) {
        return store.unscoredByClaude(model, 1000).stream().map(Job::id).filter(id -> id.startsWith("cmp-")).collect(Collectors.toSet());
    }
}
