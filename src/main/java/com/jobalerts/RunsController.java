package com.jobalerts;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/** Starts and lists pipeline runs. JSON-only POST: a cross-site form or no-cors fetch can't trigger a run. */
@RestController
class RunsController {
    private final Runs runs;

    RunsController(Runs runs) { this.runs = runs; }

    /** steps omitted = full run (fetch, score, send). */
    record Start(List<String> steps, boolean skipWorkday) {}

    @PostMapping(path = "/api/runs", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    Runs.Run start(@RequestBody Start s) {
        try {
            return runs.start(s.steps() == null ? Runs.FULL : s.steps(), s.skipWorkday(), "api");
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "a run is already going");
        }
    }

    @GetMapping("/api/runs")
    List<Runs.Run> recent() { return runs.recent(20); }

    @GetMapping(path = "/api/runs/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter events() { return runs.subscribe(); }
}
