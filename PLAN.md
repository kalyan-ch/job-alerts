# Job Alert Bot — Plan

Daily 7am Telegram message with jobs matching profile. Runs on old laptop.

## Stack

Spring Boot 4 (Java 25), `spring-boot-starter-web` only. Zero database.

Boot 4 specifics: `RestClient` for all HTTP (no `RestTemplate`), Jackson 3 (`tools.jackson.*` imports, not `com.fasterxml.*`). Java 25 is LTS.

## Flow

```
@Scheduled(cron = "0 0 7 * * *")
  fetch()   → ATS JSON boards        → List<Job>
  prefilter → rules (cheap, drops ~95%)
  score()   → Claude Haiku vs resume → 0-100 + one-line reason
  dedupe    → seen.txt
  notify()  → Telegram sendMessage
```

## 1. Fetch — direct ATS APIs

Free, structured, no keys, no rate limits.

| Board | Endpoint |
|---|---|
| Greenhouse | `https://boards-api.greenhouse.io/v1/boards/{co}/jobs?content=true` |
| Lever | `https://api.lever.co/v0/postings/{co}?mode=json` |
| Ashby | `https://api.ashbyhq.com/posting-api/job-board/{co}` |

`companies.yml` holds `{board, slug}` list — **107 verified**, every slug probed live against all three boards (2026-09-18). `probe.sh candidates.txt` regenerates it; add names to `candidates.txt` and re-run to grow.

Slug ≠ company name (`doordashusa`, `sourcegraph91`, `gleanwork`, `andurilindustries`). Probe, never guess.

Job counts in the file are board-wide across all offices. The Bay Area cut happens in the prefilter.

Normalize to `Job(id, company, title, location, url, descriptionText, postedAt)`.

## 2. Prefilter — rules

Runs before LLM so token spend stays flat. All config in `application.yml`:

- **Location**: description/location matches `San Francisco|Bay Area|Palo Alto|Mountain View|Oakland|San Jose|Sunnyvale|Redwood City|Remote.*US` — reject if matches `Remote \(non-US\)`, other metros.
- **Visa**: reject on `no sponsorship|not able to sponsor|must be authorized to work.*without sponsorship|US citizen.*required|TS/SCI|security clearance`. Silence = pass (most postings say nothing; LLM flags it later).
- **Pay**: regex `\$?(\d{2,3}),?(\d{3})` pairs from description, keep if max ≥ floor. No range found = pass (CA requires disclosure, but not all comply).
- **Title**: keyword allow/deny list.

## 3. Score — local LLM

OpenAI-compatible `POST {llmUrl}/v1/chat/completions` — Ollama, LM Studio, llama.cpp server, vLLM all speak it. Batched 5 per prompt (local context is tighter than hosted).

Input: resume text (`resume.txt`, read once at boot) + job title/company/description (truncate to 2k chars).

Output: `response_format: json_schema` → `{results: [{id, score, reason, visaRisk}]}`. Score ≥ threshold (default 70) ships. Local models still wrap JSON in prose or fences under a schema, so `Scorer.parse` cuts to the outermost `{...}`.

Cost: zero. Runs on the same laptop.

## 4. Dedupe — a text file

The only state is "have I sent this job ID before". Never queried, never joined, never ranged over. A set of strings.

```java
Set<String> seen = new HashSet<>(Files.readAllLines(path));   // boot
...
Files.write(path, newIds, CREATE, APPEND);                    // after send
```

~30k lines after a year, ~1MB. Loads in milliseconds.

`ponytail:` plain-text set, ceiling ~100k IDs. Move to SQLite only if you start asking questions of the history ("which companies posted most?", "did I ever see this in March?").

## 5. Notify — Discord

`POST {discordWebhook}` with `{content, flags: 4}` (flag 4 suppresses link embeds).

Top 10 by score, split on job boundaries at Discord's 2000-char cap:

```
**Senior Backend Engineer** — Stripe · 87
https://boards.greenhouse.io/stripe/jobs/123
```

Nothing matched → send nothing (silence is signal).

Setup: Discord channel → Edit Channel → Integrations → Webhooks → New Webhook → Copy URL.

## 6. Run on laptop

`launchd` keeps the JVM alive; Spring's `@Scheduled` owns the timing.

`~/Library/LaunchAgents/com.jobalerts.plist` → `KeepAlive=true`, `java -jar job-alerts.jar`.

Stop the Mac sleeping: `sudo pmset -a sleep 0 disablesleep 1` (lid closed, on power).

Secrets in `~/.job-alerts.env`, loaded via `spring.config.import=optional:file:...`. Never in the repo.

## Files

```
job-alerts/
  build.gradle, settings.gradle, gradlew
  companies.yml
  resume.txt
  src/main/java/.../
    JobAlertsApplication.java
    Job.java                 record
    AtsClient.java           3 fetch methods
    Prefilter.java           rules
    Scorer.java              local LLM call
    SeenStore.java           load/append seen.txt
    Discord.java             one POST
    DailyJob.java            @Scheduled, wires the 5 above
  src/main/resources/application.yml
  src/test/java/.../PrefilterTest.java
```

~450 lines. Nine classes, no interfaces, no service layer, no DB driver.

## Build order

1. `AtsClient` + `Prefilter` + test → prove job data flows
2. `Discord` → prove delivery
3. `Scorer` → plug in ranking
4. `SeenStore` + `@Scheduled` + launchd → leave it running

Run steps 1–3 by hand (`--run-once` flag) until output looks right, then schedule.

Step 1–2 alone is already useful; ship it before touching the LLM.

---

Skipped: retry/backoff on ATS fetch, metrics, web UI, multi-user.
Add when: a board starts flaking (retry), or you stop reading the messages (tune threshold, not code).
