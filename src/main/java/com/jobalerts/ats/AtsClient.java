package com.jobalerts.ats;

import com.jobalerts.config.Boards;
import com.jobalerts.domain.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
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

    /** skipWorkday drops Workday tenants; like a failed fetch, their stored jobs stay untouched. */
    public List<Boards.Company> companies(boolean skipWorkday) {
        return config.companies().stream().filter(c -> !(skipWorkday && c.board().equals("workday"))).toList();
    }

    /** Hands each board's jobs to sink on that board's thread as soon as it's fetched, so saving never waits
     *  for the slowest board. A failed fetch never reaches sink, so the store can't mistake it for "all jobs removed". */
    public void fetchAll(boolean skipWorkday, BiConsumer<String, List<Job>> sink) {
        var companies = companies(skipWorkday);
        int n = companies.size();
        var done = new AtomicInteger();
        // One virtual thread per company: total time is the slowest board, not the sum.
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            companies.forEach(c -> pool.submit(() -> fetch(c, done, n, sink)));
        }
    }

    /** Fetches every slug in companies.yml and lists the ones that error or return 0 jobs: stale entries to fix. */
    public List<String> badSlugs(boolean skipWorkday) {
        List<Future<String>> futures;
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            futures = companies(skipWorkday).stream().map(c -> pool.submit(() -> problem(c))).toList();
        }
        return futures.stream().map(Future::resultNow).filter(s -> s != null).toList();
    }

    private String problem(Boards.Company c) {
        String id = c.board() + "/" + c.slug();
        Board board = boards.get(c.board());
        if (board == null) return id + ": no Board implementation";
        try {
            return board.fetch(c.slug()).isEmpty() ? id + ": 0 jobs" : null;
        } catch (Exception e) {
            return id + ": " + brief(e);
        }
    }

    /** Error bodies can be whole HTML pages (Cloudflare challenges); the status line is what matters. */
    public static String brief(Exception e) {
        var s = e.toString();
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    private void fetch(Boards.Company c, AtomicInteger done, int n, BiConsumer<String, List<Job>> sink) {
        String id = c.board() + "/" + c.slug();
        Board board = boards.get(c.board());
        if (board == null) {
            log.warn("no Board implementation for {}", id);
            return;
        }
        List<Job> jobs;
        try {
            jobs = board.fetch(c.slug());
        } catch (Exception e) {
            log.warn("[{}/{}] {} fetch failed: {}", done.incrementAndGet(), n, id, brief(e));
            return;
        }
        log.info("[{}/{}] {}: {} jobs", done.incrementAndGet(), n, id, jobs.size());
        try {   // submit() would swallow this into an unread Future
            sink.accept(id, jobs);
        } catch (Exception e) {
            log.warn("{} save failed: {}", id, brief(e));
        }
    }
}
