package com.jobalerts.store;

import com.jobalerts.score.ClaudeScorer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Read-only API for the dashboard/ React app. */
@RestController
class JobsController {
    private final JobStore store;
    private final ClaudeScorer claude;

    JobsController(JobStore store, ClaudeScorer claude) { this.store = store; this.claude = claude; }

    @GetMapping("/api/jobs/latest")
    List<JobStore.Top> latest() { return store.latest(100); }

    /** by=claude ranks by Claude's score instead of the local model's. */
    @GetMapping("/api/jobs/top")
    List<JobStore.Top> top(@RequestParam(defaultValue = "local") String by) {
        return by.equals("claude") ? store.topByClaude(100) : store.top(100);
    }

    /** Local vs Claude on the same jobs, biggest gap first. */
    @GetMapping("/api/jobs/compare")
    List<JobStore.Compared> compare() { return store.compare(claude.model(), 200); }

    @GetMapping("/api/jobs")
    List<JobStore.Top> byCompany(@RequestParam String company) { return store.byCompany(company); }
}
