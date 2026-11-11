package com.jobalerts.store;

import com.jobalerts.config.Claude;
import com.jobalerts.domain.Job;
import com.jobalerts.domain.Scored;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** The jobs table: every prefiltered job, its LLM score, and whether Discord has had it. Soft-deleted for a dashboard. */
@Component
public class JobStore {
    // now() is the transaction start, so within one sync "last_seen < now()" means "not in today's fetch".
    // Score columns are left alone: a job already judged is never re-scored or re-sent.
    private static final String UPSERT = """
            INSERT INTO jobs (id, source, company, title, location, url, description, posted_at, first_seen, last_seen)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now())
            ON CONFLICT (id) DO UPDATE SET source = EXCLUDED.source, company = EXCLUDED.company,
                title = EXCLUDED.title, location = EXCLUDED.location, url = EXCLUDED.url,
                description = EXCLUDED.description, posted_at = EXCLUDED.posted_at,
                last_seen = now(), removed_at = NULL""";
    private static final String REMOVE_MISSING =
            "UPDATE jobs SET removed_at = now() WHERE source = ? AND removed_at IS NULL AND last_seen < now()";

    private final JdbcTemplate jdbc;
    private final String claudeModel;   // whose score the cards show beside the local one

    JobStore(JdbcTemplate jdbc, Claude claude) { this.jdbc = jdbc; this.claudeModel = claude.model(); }

    /** One board's fetch. Boards whose fetch failed are never synced, so they keep their jobs. */
    @Transactional
    public void sync(String source, List<Job> jobs) {
        jdbc.batchUpdate(UPSERT, jobs.stream().map(j -> new Object[]{
                j.id(), source, clean(j.company()), clean(j.title()), clean(j.location()), clean(j.url()),
                clean(j.descriptionText()), j.postedAt() == null ? null : j.postedAt().atOffset(ZoneOffset.UTC)
        }).toList());
        jdbc.update(REMOVE_MISSING, source);
    }

    /** Live jobs the LLM hasn't judged yet; a failed batch stays here and is retried next run. */
    public List<Job> unscored() {
        return jdbc.query("SELECT * FROM jobs WHERE removed_at IS NULL AND scored_at IS NULL", (rs, i) -> job(rs));
    }

    public void saveScores(List<Scored> scored) {
        jdbc.batchUpdate("UPDATE jobs SET score = ?, reason = ?, visa_sponsorship = ?, scored_at = now() WHERE id = ?",
                scored.stream().map(s -> new Object[]{s.score(), clean(s.reason()), s.visaSponsorship(), s.job().id()}).toList());
    }

    /** Best live jobs not yet sent to Discord. */
    /** Posted in the last day; undated boards count from when we first saw the job. */
    public List<Scored> unsent(int minScore, int limit) {
        return jdbc.query("""
                SELECT * FROM jobs WHERE removed_at IS NULL AND notified_at IS NULL AND score >= ?
                  AND COALESCE(posted_at, first_seen) > now() - interval '1 day'
                ORDER BY score DESC LIMIT ?""",
                (rs, i) -> new Scored(job(rs), rs.getObject("score", Integer.class), rs.getString("reason"), rs.getString("visa_sponsorship")),
                minScore, limit);
    }

    /** Dashboard row: no description, so 100 rows stay small. score is null until the LLM has judged it;
     *  claudeScore until the score-claude step has. */
    public record Top(String id, String company, String title, String location, String url, Instant postedAt,
                      Integer score, String reason, String visaSponsorship, boolean notified,
                      Integer claudeScore, String claudeReason) {}

    /** Newest live jobs, scored or not. Undated boards sort by when we first saw the job. */
    public List<Top> latest(int limit) {
        return tops("removed_at IS NULL ORDER BY COALESCE(posted_at, first_seen) DESC LIMIT ?", limit);
    }

    /** Highest-scored live jobs, sent or not. Ties go to the newer posting. */
    public List<Top> top(int limit) {
        return tops("removed_at IS NULL AND score IS NOT NULL ORDER BY score DESC, COALESCE(posted_at, first_seen) DESC LIMIT ?", limit);
    }

    /** Highest live jobs by the configured Claude model's score. Ties go to the newer posting. */
    public List<Top> topByClaude(int limit) {
        return tops("removed_at IS NULL AND claude_score IS NOT NULL ORDER BY claude_score DESC, COALESCE(posted_at, first_seen) DESC LIMIT ?", limit);
    }

    /** Every live job at one company, best first; unscored ones last, newest first. */
    public List<Top> byCompany(String company) {
        return tops("removed_at IS NULL AND company = ? ORDER BY score DESC NULLS LAST, COALESCE(posted_at, first_seen) DESC", company);
    }

    private List<Top> tops(String whereOrder, Object... args) {
        // Wrapped so whereOrder's bare column names (score, company...) still mean the jobs columns.
        var args2 = new Object[args.length + 1];
        args2[0] = claudeModel;
        System.arraycopy(args, 0, args2, 1, args.length);
        return jdbc.query("""
                SELECT * FROM (SELECT j.*, c.score AS claude_score, c.reason AS claude_reason
                               FROM jobs j LEFT JOIN claude_scores c ON c.job_id = j.id AND c.model = ?) jobs
                WHERE\s""" + whereOrder,
                (rs, i) -> {
                    var j = job(rs);
                    return new Top(j.id(), j.company(), j.title(), j.location(), j.url(), j.postedAt(),
                            rs.getObject("score", Integer.class), rs.getString("reason"), rs.getString("visa_sponsorship"),
                            rs.getObject("notified_at") != null,
                            rs.getObject("claude_score", Integer.class), rs.getString("claude_reason"));
                }, args2);
    }

    /** Live jobs this Claude model hasn't scored, newest first. Independent of the local model's scores. */
    public List<Job> unscoredByClaude(String model, int limit) {
        return jdbc.query("""
                SELECT j.* FROM jobs j WHERE j.removed_at IS NULL
                  AND NOT EXISTS (SELECT 1 FROM claude_scores c WHERE c.job_id = j.id AND c.model = ?)
                ORDER BY COALESCE(j.posted_at, j.first_seen) DESC LIMIT ?""", (rs, i) -> job(rs), model, limit);
    }

    public void saveClaudeScore(String model, Scored s) {
        jdbc.update("""
                INSERT INTO claude_scores (job_id, model, score, reason, visa_sponsorship) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (job_id, model) DO UPDATE SET score = EXCLUDED.score, reason = EXCLUDED.reason,
                    visa_sponsorship = EXCLUDED.visa_sponsorship, scored_at = now()""",
                s.job().id(), model, s.score(), clean(s.reason()), s.visaSponsorship());
    }

    /** One job both models scored. gap = claude - local. */
    public record Compared(String id, String company, String title, String url, int local, String localReason,
                           int claude, String claudeReason, int gap) {}

    /** Live jobs both scored, biggest disagreement first: the rows worth reading. */
    public List<Compared> compare(String model, int limit) {
        return jdbc.query("""
                SELECT j.id, j.company, j.title, j.url, j.score, j.reason, c.score AS c_score, c.reason AS c_reason
                FROM jobs j JOIN claude_scores c ON c.job_id = j.id AND c.model = ?
                WHERE j.removed_at IS NULL AND j.score IS NOT NULL
                ORDER BY abs(c.score - j.score) DESC, c.score DESC LIMIT ?""",
                (rs, i) -> new Compared(rs.getString("id"), rs.getString("company"), rs.getString("title"),
                        rs.getString("url"), rs.getInt("score"), rs.getString("reason"),
                        rs.getInt("c_score"), rs.getString("c_reason"), rs.getInt("c_score") - rs.getInt("score")),
                model, limit);
    }

    /** Clears the local model's scores so the score step judges every job again (after a prompt change).
     *  notified_at is kept, so nothing already sent goes to Discord twice. Claude's scores are left alone. */
    public void resetLocalScores() {
        jdbc.update("UPDATE jobs SET score = NULL, reason = NULL, visa_sponsorship = NULL, scored_at = NULL");
    }

    /** Clears one Claude model's scores; other models' stay for comparison. */
    public void resetClaudeScores(String model) {
        jdbc.update("DELETE FROM claude_scores WHERE model = ?", model);
    }

    public void markNotified(List<Scored> sent) {
        jdbc.batchUpdate("UPDATE jobs SET notified_at = now() WHERE id = ?",
                sent.stream().map(s -> new Object[]{s.job().id()}).toList());
    }

    private static Job job(ResultSet rs) throws SQLException {
        var posted = rs.getObject("posted_at", OffsetDateTime.class);
        return new Job(rs.getString("id"), rs.getString("company"), rs.getString("title"),
                rs.getString("location"), rs.getString("url"), rs.getString("description"),
                posted == null ? null : posted.toInstant());
    }

    /** Postgres TEXT rejects NUL; one scraped NUL would otherwise roll back the whole sync. */
    private static String clean(String s) { return s == null ? null : s.replace("\0", ""); }
}
