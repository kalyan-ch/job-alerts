package com.jobalerts;

import com.jobalerts.ats.AtsClient;
import com.jobalerts.config.Props;
import com.jobalerts.filter.Prefilter;
import com.jobalerts.notify.Discord;
import com.jobalerts.score.ClaudeScorer;
import com.jobalerts.score.Scorer;
import com.jobalerts.store.JobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** Pipeline runs, started by the API or the 10pm schedule. One at a time, each tracked in the runs table. */
@Component
public class Runs {
    private static final Logger log = LoggerFactory.getLogger(Runs.class);
    static final List<String> STEPS = List.of("reset-local", "reset-claude", "fetch", "score", "score-claude", "send", "check-slugs");   // run order
    static final List<String> FULL = List.of("fetch", "score", "send");

    public record Run(long id, String steps, boolean skipWorkday, String triggeredBy, String state, String step,
                      Integer companies, Integer companiesFailed, Integer jobsFetched, Integer jobsKept,
                      Integer jobsScored, Integer jobsSent, String badSlugs, String error,
                      OffsetDateTime startedAt, OffsetDateTime finishedAt, Integer jobsScoredClaude) {}

    private static final RowMapper<Run> RUN = new DataClassRowMapper<>(Run.class);

    private final AtsClient ats;
    private final Prefilter prefilter;
    private final Scorer scorer;
    private final ClaudeScorer claude;
    private final Discord discord;
    private final Props p;
    private final JobStore jobs;
    private final JdbcTemplate jdbc;
    private final List<SseEmitter> listeners = new CopyOnWriteArrayList<>();   // open dashboard streams

    Runs(AtsClient ats, Prefilter prefilter, Scorer scorer, ClaudeScorer claude, Discord discord, Props p, JobStore jobs, JdbcTemplate jdbc) {
        this.ats = ats; this.prefilter = prefilter; this.scorer = scorer; this.claude = claude; this.discord = discord;
        this.p = p; this.jobs = jobs; this.jdbc = jdbc;
    }

    @Scheduled(cron = "0 0 22 * * *", zone = "America/Los_Angeles")
    void daily() {
        try {
            start(FULL, false, "schedule");
        } catch (DuplicateKeyException e) {
            log.warn("10pm run skipped: another run is still going");
        }
    }

    /** Saves the row, then works the steps on a background thread. A run already going makes the insert
     *  fail on the runs_one_running index with DuplicateKeyException. */
    public Run start(List<String> steps, boolean skipWorkday, String triggeredBy) {
        if (steps.isEmpty() || !STEPS.containsAll(steps))
            throw new IllegalArgumentException("steps must be some of " + STEPS);
        var ordered = STEPS.stream().filter(steps::contains).toList();
        long id = jdbc.queryForObject(
                "INSERT INTO runs (steps, skip_workday, triggered_by, state) VALUES (?, ?, ?, 'running') RETURNING id",
                Long.class, String.join(",", ordered), skipWorkday, triggeredBy);
        log.info("run {} started: {}{}", id, ordered, skipWorkday ? ", skipping Workday" : "");
        changed();
        Thread.ofVirtual().start(() -> execute(id, ordered, skipWorkday));
        return jdbc.queryForObject("SELECT * FROM runs WHERE id = ?", RUN, id);
    }

    public List<Run> recent(int limit) {
        return jdbc.query("SELECT * FROM runs ORDER BY id DESC LIMIT ?", RUN, limit);
    }

    /** A stream that gets a "changed" event whenever a runs row does. */
    public SseEmitter subscribe() {
        var e = new SseEmitter(0L);   // no timeout; a dead one is dropped on its next send
        listeners.add(e);
        e.onCompletion(() -> listeners.remove(e));
        e.onError(x -> listeners.remove(e));
        return e;
    }

    @Scheduled(fixedRate = 60_000)   // keeps the Next proxy from closing an idle stream
    void ping() { broadcast(() -> SseEmitter.event().comment("ping")); }

    private void changed() { broadcast(() -> SseEmitter.event().data("changed")); }

    // A builder can't be sent twice, so each listener gets a fresh one.
    private void broadcast(Supplier<SseEmitter.SseEventBuilder> event) {
        for (var e : listeners) {
            try { e.send(event.get()); } catch (IOException | IllegalStateException x) { listeners.remove(e); }
        }
    }

    // ponytail: counts land when each step ends; scoring can take an hour with no live count.
    // Push per-board/per-batch increments into the row if the dashboard needs live progress.
    private void execute(long id, List<String> steps, boolean skipWorkday) {
        long t = System.currentTimeMillis();
        try {
            for (var s : steps) {
                set(id, "step = ?", s);
                switch (s) {
                    case "reset-local" -> jobs.resetLocalScores();
                    case "reset-claude" -> jobs.resetClaudeScores(claude.model());
                    case "fetch" -> fetch(id, skipWorkday);
                    case "score" -> set(id, "jobs_scored = ?", scorer.score(jobs.unscored()).size());
                    case "score-claude" -> set(id, "jobs_scored_claude = ?", claude.score());
                    case "send" -> set(id, "jobs_sent = ?", send());
                    case "check-slugs" -> checkSlugs(id, skipWorkday);
                }
            }
            set(id, "state = 'done', step = NULL, finished_at = now()");
            log.info("run {} done in {}s", id, (System.currentTimeMillis() - t) / 1000);
        } catch (Exception e) {
            log.warn("run {} failed", id, e);
            set(id, "state = 'failed', error = ?, finished_at = now()", AtsClient.brief(e));
        }
    }

    /** Fetch → prefilter → save, each board on its own thread as it lands. Only jobs worth scoring reach
     *  the table; one a config change now rejects gets soft-deleted. */
    private void fetch(long id, boolean skipWorkday) {
        int n = ats.companies(skipWorkday).size();
        var ok = new AtomicInteger();
        var all = new AtomicInteger();
        var kept = new AtomicInteger();
        set(id, "companies = ?", n);
        ats.fetchAll(skipWorkday, (source, list) -> {
            var keep = prefilter.apply(list);
            jobs.sync(source, keep);
            all.addAndGet(list.size());
            kept.addAndGet(keep.size());
            ok.incrementAndGet();
        });
        log.info("{} fetched, {} past prefilter and saved, {}/{} boards failed", all, kept, n - ok.get(), n);
        set(id, "companies_failed = ?, jobs_fetched = ?, jobs_kept = ?", n - ok.get(), all.get(), kept.get());
    }

    /** Sends the best unsent. Marked after the send, so a Discord failure leaves them for next run. */
    private int send() {
        var top = jobs.unsent(p.minScore(), p.maxJobs());
        log.info("{} unsent at or above score {}", top.size(), p.minScore());
        discord.send(top);
        jobs.markNotified(top);
        return top.size();
    }

    /** No LLM, no Discord, no jobs writes: just which companies.yml entries are broken. */
    private void checkSlugs(long id, boolean skipWorkday) {
        set(id, "companies = ?", ats.companies(skipWorkday).size());
        var bad = ats.badSlugs(skipWorkday);
        bad.forEach(s -> log.warn("BAD {}", s));
        set(id, "companies_failed = ?, bad_slugs = ?", bad.size(), String.join("\n", bad));
    }

    private void set(long id, String assignments, Object... args) {
        jdbc.update("UPDATE runs SET " + assignments + " WHERE id = ?",
                Stream.concat(Arrays.stream(args), Stream.of(id)).toArray());
        changed();
    }
}
