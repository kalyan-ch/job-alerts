package com.jobalerts.store;

import com.jobalerts.config.Props;
import com.jobalerts.domain.Scored;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;

/** Every LLM score ever produced, one JSON line per job, so re-thresholding or resending costs no LLM calls. */
@Component
public class ScoreCache {
    private static final JsonMapper JSON = new JsonMapper();

    private final Path path;
    private final Map<String, Scored> byId = new ConcurrentHashMap<>();

    @Autowired
    ScoreCache(Props p) throws IOException { this(p.scoresFile()); }

    ScoreCache(Path path) throws IOException {
        this.path = path;
        if (Files.exists(path)) for (String line : Files.readAllLines(path)) {
            if (line.isBlank()) continue;
            var s = JSON.readValue(line, Scored.class);
            byId.put(s.job().id(), s);   // later lines win
        }
    }

    public Scored get(String id) { return byId.get(id); }

    public List<Scored> all() { return new ArrayList<>(byId.values()); }

    /** Written per batch so a Ctrl-C mid-run keeps everything scored so far. */
    public synchronized void add(List<Scored> scored) throws IOException {
        if (scored.isEmpty()) return;
        Files.write(path, scored.stream().map(JSON::writeValueAsString).toList(), CREATE, APPEND);
        scored.forEach(s -> byId.put(s.job().id(), s));
    }
}
