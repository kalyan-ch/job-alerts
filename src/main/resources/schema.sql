-- Every job any board has listed. removed_at is set when its board stops listing it, cleared if it comes back.
CREATE TABLE IF NOT EXISTS jobs (
    id          TEXT PRIMARY KEY,
    source      TEXT NOT NULL,          -- board/slug from companies.yml; the unit a sync adds/removes within
    company     TEXT NOT NULL,
    title       TEXT NOT NULL,
    location    TEXT,
    url         TEXT,
    description TEXT,
    posted_at   TIMESTAMPTZ,
    first_seen  TIMESTAMPTZ NOT NULL,
    last_seen   TIMESTAMPTZ NOT NULL,
    removed_at  TIMESTAMPTZ
);

-- Added after the table shipped; IF NOT EXISTS upgrades an existing table in place.
-- ponytail: hand-rolled migration; move to Flyway once a change can't be written idempotently.
ALTER TABLE jobs
    ADD COLUMN IF NOT EXISTS score       INT,
    ADD COLUMN IF NOT EXISTS reason      TEXT,
    ADD COLUMN IF NOT EXISTS visa_sponsorship TEXT CHECK (visa_sponsorship IN ('yes', 'no', 'unknown')),
    ADD COLUMN IF NOT EXISTS scored_at   TIMESTAMPTZ,   -- null = LLM hasn't judged it (or its batch failed)
    ADD COLUMN IF NOT EXISTS notified_at TIMESTAMPTZ;   -- null = not sent to Discord yet

-- One row per pipeline run (API or 10pm schedule). Counts stay null for steps the run didn't include.
CREATE TABLE IF NOT EXISTS runs (
    id               BIGSERIAL PRIMARY KEY,
    steps            TEXT NOT NULL,          -- in run order: fetch,score,send,check-slugs
    skip_workday     BOOLEAN NOT NULL,
    triggered_by     TEXT NOT NULL,          -- api | schedule
    state            TEXT NOT NULL CHECK (state IN ('running', 'done', 'failed')),
    step             TEXT,                   -- step in progress while running
    companies        INT,                    -- companies.yml entries in this run, after skip-workday
    companies_failed INT,                    -- fetch errored; for check-slugs also 0 jobs
    jobs_fetched     INT,
    jobs_kept        INT,                    -- past prefilter and saved
    jobs_scored      INT,
    jobs_sent        INT,
    bad_slugs        TEXT,                   -- check-slugs output, one per line
    error            TEXT,
    started_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at      TIMESTAMPTZ
);
-- At most one running row: a second start fails on insert, so two runs never share the LLM or double-send.
CREATE UNIQUE INDEX IF NOT EXISTS runs_one_running ON runs ((true)) WHERE state = 'running';
-- Runs on every start: a row still "running" means the server stopped mid-run.
UPDATE runs SET state = 'failed', error = 'server stopped mid-run', finished_at = now() WHERE state = 'running';

-- Claude's scores, apart from jobs.score (the local model's, which drives Discord), so the two can be compared.
-- One row per job per model, so two Claude models can be compared too.
CREATE TABLE IF NOT EXISTS claude_scores (
    job_id           TEXT NOT NULL REFERENCES jobs (id),
    model            TEXT NOT NULL,
    score            INT NOT NULL,
    reason           TEXT,
    visa_sponsorship TEXT CHECK (visa_sponsorship IN ('yes', 'no', 'unknown')),
    scored_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (job_id, model)
);
ALTER TABLE runs ADD COLUMN IF NOT EXISTS jobs_scored_claude INT;
