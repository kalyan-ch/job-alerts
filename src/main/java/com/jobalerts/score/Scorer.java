package com.jobalerts.score;

import com.jobalerts.config.Props;
import com.jobalerts.domain.Job;
import com.jobalerts.domain.Scored;
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
import java.util.stream.Collectors;

/** Scores jobs against the resume with a local OpenAI-compatible LLM (Ollama, LM Studio, llama.cpp, vLLM). */
@Component
public class Scorer {
    private static final Logger log = LoggerFactory.getLogger(Scorer.class);
    private static final int BATCH = 5;          // local models hold shorter context than a hosted one
    private static final int DESC_CHARS = 2000;
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
    private final String resume;

    Scorer(Props p) throws IOException {
        this.p = p;
        this.resume = Files.readString(p.resumeFile());
        this.http = RestClient.builder().baseUrl(p.llmUrl()).build();
    }

    public List<Scored> score(List<Job> jobs) {
        var out = new ArrayList<Scored>();
        for (int i = 0; i < jobs.size(); i += BATCH) {
            var batch = jobs.subList(i, Math.min(i + BATCH, jobs.size()));
            try {
                out.addAll(scoreBatch(batch));
            } catch (Exception e) {
                log.warn("scoring batch failed: {}", e.toString());
            }
        }
        log.info("scored {}/{} jobs", out.size(), jobs.size());
        return out;
    }

    private List<Scored> scoreBatch(List<Job> batch) {
        var byId = batch.stream().collect(Collectors.toMap(Job::id, j -> j));
        var body = Map.of(
                "model", p.model(),
                "temperature", 0,
                "stream", false,
                "response_format", Map.of("type", "json_schema",
                        "json_schema", Map.of("name", "job_scores", "strict", true, "schema", SCHEMA)),
                "messages", List.of(
                        Map.of("role", "system", "content",
                                "Rate how well each job matches the candidate's resume. Score 0-100. "
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
