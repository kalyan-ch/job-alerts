package com.jobalerts.store;

import com.jobalerts.config.Props;
import com.jobalerts.domain.Job;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;

/** The only state: "have I sent this job ID before". A set of strings in a text file. */
@Component
public class SeenStore {
    private final Path path;
    private final Set<String> seen;

    SeenStore(Props p) throws IOException {
        this.path = p.seenFile();
        this.seen = Files.exists(path) ? new HashSet<>(Files.readAllLines(path)) : new HashSet<>();
    }

    public boolean isNew(Job j) { return !seen.contains(j.id()); }

    public void record(List<Job> jobs) throws IOException {
        var ids = jobs.stream().map(Job::id).filter(seen::add).toList();
        if (!ids.isEmpty()) Files.write(path, ids, CREATE, APPEND);
    }
}
