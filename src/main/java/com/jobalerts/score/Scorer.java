package com.jobalerts.score;

import com.jobalerts.config.Props;
import com.jobalerts.domain.Job;
import com.jobalerts.domain.Scored;
import com.jobalerts.store.ScoreCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/** Scores jobs against the resume with a local OpenAI-compatible LLM (Ollama, LM Studio, llama.cpp, vLLM). */
@Component
public class Scorer {
    private static final Logger log = LoggerFactory.getLogger(Scorer.class);
    private static final int BATCH = 8;          // 8 x 1.5k-char descriptions + resume fits omlx's 32k context
    private static final int DESC_CHARS = 1500;
    // omlx's max_concurrent_requests is 8; going past it only queues on the server side.
    private static final int PARALLEL = 8;
    private static final int MAX_TOKENS = 1300;  // ~8 short results; stops a rambling model eating minutes
    private static final JsonMapper JSON = new JsonMapper();

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of("results", Map.of(
                    "type", "array",
                    "items", Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "id", Map.of("type", "string"),
                                    "score", Map.of("type", "integer"),
                                    "reason", Map.of("type", "string"),
                                    "visaRisk", Map.of("type", "boolean")),
                            "required", List.of("id", "score", "reason", "visaRisk"),
                            "additionalProperties", false))),
            "required", List.of("results"),
            "additionalProperties", false);

    private final RestClient http;
    private final Props p;
    private final ScoreCache cache;
    private final String resume;

    Scorer(Props p, ScoreCache cache) throws IOException {
        this.p = p;
        this.cache = cache;
        this.resume = Files.readString(p.resumeFile());
        this.http = RestClient.builder().baseUrl(p.llmUrl())
                .defaultHeader("Authorization", "Bearer " + p.llmKey()).build();
    }

    public List<Scored> score(List<Job> all) {
        var out = new ArrayList<Scored>();
        var jobs = new ArrayList<Job>();
        for (Job j : all) {
            var hit = cache.get(j.id());
            if (hit != null) out.add(hit); else jobs.add(j);
        }
        int batches = (jobs.size() + BATCH - 1) / BATCH;
        log.info("{} cached, scoring {} jobs in {} batches ({} at a time) with {} at {}",
                out.size(), jobs.size(), batches, PARALLEL, p.model(), p.llmUrl());
        var done = new AtomicInteger();
        List<Future<List<Scored>>> futures;
        try (var pool = Executors.newFixedThreadPool(PARALLEL)) {
            futures = new ArrayList<>();
            for (int i = 0; i < jobs.size(); i += BATCH) {
                var batch = jobs.subList(i, Math.min(i + BATCH, jobs.size()));
                futures.add(pool.submit(() -> scoreLogged(batch, done, batches)));
            }
        }
        for (var f : futures) out.addAll(f.resultNow());
        log.info("have scores for {}/{} jobs", out.size(), all.size());
        return out;
    }

    private List<Scored> scoreLogged(List<Job> batch, AtomicInteger done, int batches) {
        long t = System.currentTimeMillis();
        try {
            var scored = scoreBatch(batch);
            cache.add(scored);
            log.info("batch {}/{}: {}/{} scored in {}s", done.incrementAndGet(), batches, scored.size(), batch.size(),
                    (System.currentTimeMillis() - t) / 1000);
            scored.forEach(s -> log.info("  {} · {} — {} ({})", s.score(), s.job().company(), s.job().title(), s.reason()));
            return scored;
        } catch (Exception e) {
            log.warn("batch {}/{} failed: {}", done.incrementAndGet(), batches, e.toString());
            return List.of();
        }
    }

    private List<Scored> scoreBatch(List<Job> batch) {
        var byId = batch.stream().collect(Collectors.toMap(Job::id, j -> j));
        var body = Map.of(
                "model", p.model(),
                "temperature", 0,
                "max_tokens", MAX_TOKENS,
                "stream", false,
                "response_format", Map.of("type", "json_schema",
                        "json_schema", Map.of("name", "job_scores", "strict", true, "schema", SCHEMA)),
                "messages", List.of(
                        Map.of("role", "system", "content",
                                "Rate how well each job matches the candidate's resume. Score 0-100. "
                                        + "The candidate targets Senior or Staff level only. Score below 40 if the role's level "
                                        + "is above that (Senior Staff, Principal, L7+, Distinguished) or below it (junior, new grad, "
                                        + "SDE I, L3/L4). Level fit matters more than topic overlap. "
                                        + "Score below 40 if the role's core platform or language (e.g. Android, iOS, embedded, "
                                        + "a specific stack) does not appear in the resume; generic skills like 'distributed "
                                        + "systems' or 'AI' do not make up for a missing core stack. "
                                        + "Set visaRisk true if the posting implies citizenship, clearance, or no sponsorship. "
                                        + "reason is one short line. Return one result per job id, JSON only.\n\nRESUME:\n" + resume),
                        Map.of("role", "user", "content", prompt(batch))));

        JsonNode res = http.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(JsonNode.class);

        var scored = new ArrayList<Scored>();
        for (var r : parse(res.path("choices").path(0).path("message").path("content").asString("")).path("results")) {
            Job j = byId.get(r.path("id").asString());
            if (j != null) scored.add(new Scored(j, r.path("score").asInt(),
                    r.path("reason").asString(""), r.path("visaRisk").asBoolean(false)));
        }
        return scored;
    }

    /** Local models wrap JSON in prose or ```json fences even under a schema — cut to the outermost object. */
    public static JsonNode parse(String content) {
        int start = content.indexOf('{'), end = content.lastIndexOf('}');
        if (start < 0 || end <= start) return JSON.createObjectNode();
        return JSON.readTree(content.substring(start, end + 1));
    }

    private static String prompt(List<Job> batch) {
        var sb = new StringBuilder();
        for (Job j : batch) {
            sb.append("---\nid: ").append(j.id())
              .append("\ncompany: ").append(j.company())
              .append("\ntitle: ").append(j.title())
              .append("\nlocation: ").append(j.location())
              .append("\ndescription: ")
              .append(j.descriptionText().substring(0, Math.min(DESC_CHARS, j.descriptionText().length())))
              .append('\n');
        }
        return sb.toString();
    }
}
