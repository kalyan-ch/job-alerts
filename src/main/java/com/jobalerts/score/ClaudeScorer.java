package com.jobalerts.score;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonSchemaLocalValidation;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredOutputConfig;
import com.jobalerts.config.Claude;
import com.jobalerts.config.Props;
import com.jobalerts.domain.Job;
import com.jobalerts.domain.Scored;
import com.jobalerts.store.JobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Scores every live job Claude hasn't, with the same prompt as the local model, into claude_scores.
 *  Doesn't wait for local scores. Never touches jobs.score, so Discord keeps following the local model. */
@Component
public class ClaudeScorer {
    private static final Logger log = LoggerFactory.getLogger(ClaudeScorer.class);
    private static final int PARALLEL = 8;
    private static final long MAX_TOKENS = 4000;   // thinking can't be turned off on current models and counts toward this
    private static final List<String> VISA = List.of("yes", "no", "unknown");

    /** The reply's schema. reason comes first so the score is written after it. */
    record Verdict(String reason, int score, String visaSponsorship) {}

    private final Claude cfg;
    private final JobStore store;
    private final String system;
    private final AnthropicClient client;   // null until a key is set: the local scorer must still run without one

    ClaudeScorer(Props p, Claude cfg, JobStore store) throws IOException {
        this.cfg = cfg;
        this.store = store;
        this.system = Scorer.instructions(Files.readString(p.resumeFile()));
        this.client = cfg.apiKey().isBlank() ? null : AnthropicOkHttpClient.builder().apiKey(cfg.apiKey()).build();
    }

    public String model() { return cfg.model(); }

    /** Returns how many were scored. A failed or refused job has no row, so the next run retries it. */
    public int score() {
        if (client == null) throw new IllegalStateException("set ANTHROPIC_API_KEY in secrets.env to score with Claude");
        var jobs = store.unscoredByClaude(cfg.model(), cfg.maxJobs());
        log.info("scoring {} jobs with {} (cap {})", jobs.size(), cfg.model(), cfg.maxJobs());
        var ok = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(PARALLEL)) {
            jobs.forEach(j -> pool.submit(() -> scoreLogged(j, ok)));
        }
        log.info("claude scored {}/{} jobs", ok.get(), jobs.size());
        return ok.get();
    }

    private void scoreLogged(Job j, AtomicInteger ok) {
        try {
            var s = scoreOne(j);
            if (s == null) return;
            store.saveClaudeScore(cfg.model(), s);
            ok.incrementAndGet();
            log.info("  claude {} · {} — {} ({})", s.score(), j.company(), j.title(), s.reason());
        } catch (Exception e) {
            log.warn("claude failed on {}: {}", j.id(), e.toString());
        }
    }

    private Scored scoreOne(Job j) {
        var params = MessageCreateParams.builder()
                .model(cfg.model())
                .maxTokens(MAX_TOKENS)
                .system(system)
                .cacheControl(CacheControlEphemeral.builder().build())   // the resume prefix repeats on every job
                .outputConfig(StructuredOutputConfig.<Verdict>builder()
                        .effort(OutputConfig.Effort.LOW)   // a short judgement, not a hard problem
                        .format(Verdict.class, JsonSchemaLocalValidation.YES)
                        .build())
                .addUserMessage(Scorer.prompt(List.of(j)));
        if (!cfg.model().contains("haiku")) {   // Haiku has no server-side fallback
            params.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                  .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
        var res = client.messages().create(params.build());
        if (res.stopReason().filter(StopReason.REFUSAL::equals).isPresent()) {
            log.warn("claude declined {}", j.id());
            return null;
        }
        var v = res.content().stream().flatMap(b -> b.text().stream()).findFirst()
                .orElseThrow(() -> new IllegalStateException("no text block, stop " + res.stopReason())).text();
        return new Scored(j, v.score(), v.reason(), VISA.contains(v.visaSponsorship()) ? v.visaSponsorship() : "unknown");
    }
}
