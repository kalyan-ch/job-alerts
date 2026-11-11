# Running job-alerts

Start Postgres first (keeps data across restarts):

```
docker compose up -d
```

## Server

```
./gradlew bootRun                            # API on http://localhost:8080 (localhost only)
cd dashboard && npm install && npm run dev   # UI on http://localhost:3001
```

One server does everything: serves the dashboard API, runs the full pipeline daily at 10pm Pacific, and starts runs on request. Ctrl-C to stop; a run cut off mid-way is marked `failed` on next start (scores saved so far are kept).

## Runs

Start from the dashboard's **Runs** tab, or with curl. One run at a time: a second start gets `409`, and the 10pm run skips if one is going.

```
curl -X POST localhost:8080/api/runs -H 'Content-Type: application/json' -d '{}'                                  # full: fetch, score, send
curl -X POST localhost:8080/api/runs -H 'Content-Type: application/json' -d '{"steps":["fetch"]}'
curl -X POST localhost:8080/api/runs -H 'Content-Type: application/json' -d '{"steps":["score","send"],"skipWorkday":true}'
curl localhost:8080/api/runs                                                                                        # last 20 runs
```

| Step | What it does |
|---|---|
| `reset-local` | Clears the local model's scores so `score` judges every live job again. Already-sent jobs stay marked sent. Dashboard: **Rescore local**. |
| `reset-claude` | Deletes the `CLAUDE_MODEL` model's rows in `claude_scores` so `score-claude` judges them again; other models' rows stay. Dashboard: **Rescore Claude**. Both resets plus both score steps: **Rescore all**. |
| `fetch` | Fetch all boards → prefilter → save to DB per board. No LLM, no Discord. |
| `score` | Score jobs in the DB with no score yet. Use after LLM failures or a rescore. |
| `score-claude` | Score every live job Claude hasn't scored yet (`claude_scores` table), local score or not, up to `CLAUDE_MAX_JOBS` (1000) per run, newest first. Never affects Discord. Not in a full run. Results in the dashboard's **Compare** tab. Needs `ANTHROPIC_API_KEY`; set `CLAUDE_MODEL` to switch models (default `claude-opus-5-5`). |
| `send` | Send the best unsent (score ≥ min-score, posted in last 24h) to Discord. |
| `check-slugs` | No DB, LLM or Discord. Lists companies.yml entries that error or return 0 jobs (shown in the run row). |

Steps always run in the order above, whatever order you list them. `skipWorkday` skips Workday tenants (slowest, Cloudflare 429s); their DB jobs are left untouched, not removed.

Each run is a row in the `runs` table: steps, state, current step, companies, companies failed, jobs fetched / kept / scored / sent, bad slugs, error, start and finish times. Counts land when each step ends, not live.

## Dashboard

Tabs: **Latest** (100 newest live jobs, scored or not), **Top scored** (100 best scored), **Runs** (start buttons + last 20 runs, auto-refreshes while one is going). The page calls the API server-side; point it elsewhere with `JOBS_API=http://host:port npm run dev`.

## Config at startup

Every `jobalerts.*` key in `application.yml` can be overridden when starting the server:

```
./gradlew bootRun --args='--jobalerts.min-score=60 --jobalerts.max-jobs=50'
```

| Key | Default | Meaning |
|---|---|---|
| `jobalerts.min-score` | 70 | Lowest score sent to Discord |
| `jobalerts.max-jobs` | 100 | Max jobs per Discord send (posted in last 24h) |
| `jobalerts.max-age-days` | 30 | Older postings never reach the DB |
| `jobalerts.pay-floor` | 180000 | Drop jobs whose top listed pay is below this |
| `jobalerts.location-regex` | see yml | Location must match (case-insensitive) |
| `jobalerts.resume-file` | resume.txt | Resume the LLM scores against |

## Environment (secrets.env or shell)

| Variable | Default |
|---|---|
| `LLM_URL` | `http://localhost:8000` |
| `LLM_API_KEY` | empty |
| `LLM_MODEL` | `DeepSeek-Coder-V2-Lite-Instruct-4bit-mlx` |
| `DISCORD_WEBHOOK` | empty |
| `DB_URL` | `jdbc:postgresql://localhost:5433/jobalerts` |
| `DB_USER` / `DB_PASSWORD` | `jobalerts` |

## Useful SQL

```sql
-- rescore: use the reset-local / reset-claude steps (Rescore buttons in the dashboard) instead

-- resend: allow already-sent jobs to go to Discord again
UPDATE jobs SET notified_at=NULL;

-- hard-delete soft-deleted jobs (their Claude scores first: claude_scores references jobs)
DELETE FROM claude_scores WHERE job_id IN (SELECT id FROM jobs WHERE removed_at IS NOT NULL);
DELETE FROM jobs WHERE removed_at IS NOT NULL;
```

Connect: `docker compose exec postgres-db psql -U jobalerts`
