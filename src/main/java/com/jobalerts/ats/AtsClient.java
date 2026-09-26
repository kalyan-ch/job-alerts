package com.jobalerts.ats;

import com.jobalerts.config.Boards;
import com.jobalerts.domain.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Fans companies.yml out across the Board implementations. */
@Component
public class AtsClient {
    private static final Logger log = LoggerFactory.getLogger(AtsClient.class);

    private final Boards config;
    private final Map<String, Board> boards;

    AtsClient(Boards config, List<Board> boards) {
        this.config = config;
        this.boards = boards.stream().collect(Collectors.toMap(Board::name, Function.identity()));
    }

    public List<Job> fetchAll() {
        int n = config.companies().size();
        var done = new AtomicInteger();
        List<Future<List<Job>>> futures;
        // One virtual thread per company: total time is the slowest board, not the sum.
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            futures = config.companies().stream().map(c -> pool.submit(() -> fetch(c, done, n))).toList();
        }
        var out = new ArrayList<Job>();
        for (var f : futures) out.addAll(f.resultNow());
        log.info("fetched {} jobs from {} boards", out.size(), n);
        return out;
    }

    private List<Job> fetch(Boards.Company c, AtomicInteger done, int n) {
        Board board = boards.get(c.board());
        if (board == null) {
            log.warn("no Board implementation for '{}' ({})", c.board(), c.slug());
            return List.of();
        }
        try {
            var jobs = board.fetch(c.slug());
            log.info("[{}/{}] {}/{}: {} jobs", done.incrementAndGet(), n, c.board(), c.slug(), jobs.size());
            return jobs;
        } catch (Exception e) {
            log.warn("[{}/{}] {}/{} fetch failed: {}", done.incrementAndGet(), n, c.board(), c.slug(), e.toString());
            return List.of();
        }
    }
}
