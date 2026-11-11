// Server component: fetches from Spring on the server, so no proxy or CORS.
import { redirect } from 'next/navigation'

export const dynamic = 'force-dynamic'   // render per request; build doesn't need the API up

const API = process.env.JOBS_API ?? 'http://localhost:8080'
const tier = (s) => (s == null ? 'none' : s >= 85 ? 'high' : s >= 70 ? 'mid' : 'low')

function ago(iso) {
  if (!iso) return 'date unknown'
  const days = Math.floor((Date.now() - new Date(iso)) / 86_400_000)
  return days <= 0 ? 'today' : days === 1 ? 'yesterday' : days < 30 ? `${days}d ago` : `${Math.floor(days / 30)}mo ago`
}

// Tabs are plain links (?tab=top), so they work with no client JS and survive a reload.
const TABS = {
  latest: { label: 'Latest', sub: (n) => `Latest ${n} jobs, newest first`, path: '/api/jobs/latest' },
  top: { label: 'Top scored', sub: (n) => `Top ${n} scored jobs, best first`, path: '/api/jobs/top' },
  compare: { label: 'Compare', sub: (n) => `${n} jobs scored by both models, biggest gap first`, path: '/api/jobs/compare' },
  runs: { label: 'Runs', sub: (n) => `Last ${n} runs`, path: '/api/runs' },
}

async function fetchJobs(path) {
  const r = await fetch(`${API}${path}`, { cache: 'no-store' })
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
  return r.json()
}

function Stat({ label, value }) {
  return (
    <div className="stat">
      <span className="stat-value">{value}</span>
      <span className="stat-label">{label}</span>
    </div>
  )
}

// One model's judgement: labelled score chip with its reason beside it.
function Verdict({ label, score, reason }) {
  return (
    <div className="verdict">
      <div className={`score ${tier(score)}`} aria-label={score == null ? `${label}: not scored` : `${label} score ${score}`}>
        {score ?? '—'}
      </div>
      <div>
        <div className="verdict-label">{label}</div>
        <p className="reason">{reason || (score == null ? 'Not scored yet' : '')}</p>
      </div>
    </div>
  )
}

// Title and facts on top; the two verdicts side by side below, so reasons read as a pair.
function JobCard({ j }) {
  return (
    <article className="card">
      <div className="card-head">
        <div className="body">
          <a className="title" href={j.url} target="_blank" rel="noreferrer">{j.title}</a>
          <div className="meta">
            <a className="company" href={`?company=${encodeURIComponent(j.company)}`}>{j.company}</a>
            <span>{j.location || 'Location not listed'}</span>
            <span>{ago(j.postedAt)}</span>
          </div>
        </div>
        <div className="tags">
          {j.visaSponsorship && <span className={`tag visa-${j.visaSponsorship}`}>visa: {j.visaSponsorship}</span>}
          {j.notified && <span className="tag">sent</span>}
        </div>
      </div>
      <div className="verdicts">
        <Verdict label="Local" score={j.score} reason={j.reason} />
        {j.claudeScore != null && <Verdict label="Claude" score={j.claudeScore} reason={j.claudeReason} />}
      </div>
    </article>
  )
}

const PRESETS = [
  ['fetch,score,send', 'Full run', 'Fetch, score, send to Discord'],
  ['fetch', 'Fetch only', 'No LLM, no Discord'],
  ['score,send', 'Score + send', 'Score unscored jobs, then send'],
  ['reset-local,reset-claude,score,score-claude', 'Rescore all', 'Clear local and Claude scores, then score both again. Slow; uses API credits'],
  ['reset-local,score', 'Rescore local', 'Clear local scores and score every job again. Slow; Claude scores kept'],
  ['reset-claude,score-claude', 'Rescore Claude', 'Clear this Claude model\'s scores and score again. Uses API credits; local scores kept'],
  ['score-claude', 'Claude score', 'Score every job Claude has not scored yet. Uses API credits'],
  ['check-slugs', 'Check slugs', 'List broken companies.yml entries'],
]

// Server Action: runs on the Next server, so Spring stays on localhost. Next checks Origin, so no CSRF.
async function startRun(fd) {
  'use server'
  const r = await fetch(`${API}/api/runs`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ steps: String(fd.get('steps')).split(','), skipWorkday: fd.get('skipWorkday') === 'on' }),
  })
  const error = r.ok ? '' : r.status === 409 ? 'A run is already going' : `HTTP ${r.status}`
  redirect(error ? `/?tab=runs&error=${encodeURIComponent(error)}` : '/?tab=runs')
}

const when = (iso) => new Date(iso).toLocaleString('en-US', { dateStyle: 'short', timeStyle: 'short' })
const took = (r) => `${Math.round((new Date(r.finishedAt ?? Date.now()) - new Date(r.startedAt)) / 60_000)}m`
const n = (v) => v ?? '—'

function RunsView({ runs, error }) {
  const running = runs.some((r) => r.state === 'running')
  return (
    <>
      <form action={startRun} className="run-form">
        <div className="presets">
          {PRESETS.map(([steps, label, hint]) => (
            <button key={steps} name="steps" value={steps} disabled={running} title={hint}>{label}</button>
          ))}
        </div>
        <label><input type="checkbox" name="skipWorkday" /> Skip Workday</label>
      </form>
      {error && <p className="error" role="alert">{error}</p>}
      {running && <p className="sub">A run is going; this page updates as it moves.</p>}
      {runs.length === 0
        ? <div className="empty"><h2>No runs yet</h2><p>Start one above, or wait for the 10pm run.</p></div>
        : (
          <div className="table-wrap">
            <table className="runs">
              <thead>
                <tr><th>#</th><th>Started</th><th>Steps</th><th>State</th><th>Companies</th><th>Failed</th>
                  <th>Fetched</th><th>Kept</th><th>Scored</th><th>Claude</th><th>Sent</th><th>Took</th></tr>
              </thead>
              <tbody>
                {runs.map((r) => (
                  <tr key={r.id}>
                    <td>{r.id}</td>
                    <td>{when(r.startedAt)}<div className="muted">{r.triggeredBy}</div></td>
                    <td>{r.steps}{r.skipWorkday && <div className="muted">no Workday</div>}</td>
                    <td>
                      <span className={`tag state-${r.state}`}>{r.state === 'running' ? `running: ${r.step}` : r.state}</span>
                      {r.error && <div className="muted">{r.error}</div>}
                      {r.badSlugs && <details><summary>bad slugs</summary><pre>{r.badSlugs}</pre></details>}
                    </td>
                    <td>{n(r.companies)}</td><td>{n(r.companiesFailed)}</td><td>{n(r.jobsFetched)}</td>
                    <td>{n(r.jobsKept)}</td><td>{n(r.jobsScored)}</td><td>{n(r.jobsScoredClaude)}</td><td>{n(r.jobsSent)}</td><td>{took(r)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
    </>
  )
}

// Same 70 cutoff as min-score: "agree" means both would (or wouldn't) send the job to Discord.
function CompareView({ rows }) {
  if (rows.length === 0) return <div className="empty"><h2>Nothing to compare yet</h2><p>Run <b>Claude score</b> from the <a href="?tab=runs">Runs</a> tab.</p></div>
  return (
    <div className="table-wrap">
      <table className="runs compare">
        <thead><tr><th>Job</th><th>Local</th><th>Claude</th><th>Gap</th><th>Why (local / Claude)</th></tr></thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.id}>
              <td><a href={r.url} target="_blank" rel="noreferrer">{r.title}</a><div className="muted">{r.company}</div></td>
              <td><span className={`score ${tier(r.local)}`}>{r.local}</span></td>
              <td><span className={`score ${tier(r.claude)}`}>{r.claude}</span></td>
              <td>{r.gap > 0 ? `+${r.gap}` : r.gap}</td>
              <td><div>{r.localReason}</div><div className="muted">{r.claudeReason}</div></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

// Pinned company tabs: same view as clicking a company name on a card.
const COMPANY_TABS = ['netflix', 'nvidia', 'databricks', 'google', 'apple']

function Tabs({ tab, company }) {
  return (
    <nav className="tabs">
      {Object.entries(TABS).map(([k, v]) => (
        <a key={k} href={`?tab=${k}`} className="tab" aria-current={k === tab ? 'page' : undefined}>{v.label}</a>
      ))}
      <span className="tab-sep" aria-hidden="true" />
      {COMPANY_TABS.map((c) => (
        <a key={c} href={`?company=${c}`} className="tab company-tab" aria-current={c === company ? 'page' : undefined}>{c}</a>
      ))}
    </nav>
  )
}

// Top tab only. claude fetches Claude's own top 100 (server-side); date re-sorts the local top 100, undated last.
const SORTS = { score: 'Local score', claude: 'Claude score', date: 'Date' }

function SortLinks({ sort }) {
  return (
    <nav className="sort" aria-label="Sort">
      Sort by
      {Object.entries(SORTS).map(([k, label]) => (
        <a key={k} href={`?tab=top&sort=${k}`} aria-current={k === sort ? 'page' : undefined}>{label}</a>
      ))}
    </nav>
  )
}

export default async function Page({ searchParams }) {
  const { tab: t, error, company, sort: s } = await searchParams
  // ?company=x lists every saved job at x; no tab is highlighted then.
  const tab = company ? null : Object.hasOwn(TABS, t ?? '') ? t : 'latest'
  const sort = Object.hasOwn(SORTS, s ?? '') ? s : 'score'
  const path = company ? `/api/jobs?company=${encodeURIComponent(company)}`
    : tab === 'top' && sort === 'claude' ? '/api/jobs/top?by=claude' : TABS[tab].path
  let jobs
  try {
    jobs = await fetchJobs(path)
  } catch (e) {
    return (
      <main>
        <div className="empty">
          <h2>Can’t reach the jobs API</h2>
          <p>{API}: {e.message}. Is the Spring app running?</p>
        </div>
      </main>
    )
  }

  if (tab === 'runs') return (
    <main>
      <header>
        <div>
          <h1>Job Alerts</h1>
          <p className="sub">{TABS.runs.sub(jobs.length)}</p>
        </div>
      </header>
      <Tabs tab={tab} />
      <RunsView runs={jobs} error={error} />
    </main>
  )

  if (tab === 'compare') {
    const agree = jobs.filter((r) => (r.local >= 70) === (r.claude >= 70)).length
    const avgGap = jobs.length ? Math.round(jobs.reduce((t, r) => t + Math.abs(r.gap), 0) / jobs.length) : 0
    return (
      <main>
        <header>
          <div>
            <h1>Job Alerts</h1>
            <p className="sub">{TABS.compare.sub(jobs.length)}</p>
          </div>
          <div className="stats">
            <Stat label="compared" value={jobs.length} />
            <Stat label="avg gap" value={avgGap} />
            <Stat label="agree on 70+" value={jobs.length ? `${Math.round((100 * agree) / jobs.length)}%` : '—'} />
          </div>
        </header>
        <Tabs tab={tab} />
        <CompareView rows={jobs} />
      </main>
    )
  }

  if (tab === 'top' && sort === 'date') jobs.sort((a, b) => new Date(b.postedAt ?? 0) - new Date(a.postedAt ?? 0))
  const scored = jobs.filter((j) => j.score != null)
  const claudeScored = jobs.filter((j) => j.claudeScore != null)
  return (
    <main>
      <header>
        <div>
          <h1>Job Alerts</h1>
          <p className="sub">
            {company ? `All ${jobs.length} saved jobs at ${company}, best first`
              : tab === 'top' && sort === 'date' ? `Top ${jobs.length} scored jobs, newest first`
              : tab === 'top' && sort === 'claude' ? `Top ${jobs.length} jobs by Claude's score, best first`
              : TABS[tab].sub(jobs.length)}
          </p>
        </div>
        <div className="stats">
          <Stat label="jobs" value={jobs.length} />
          <Stat label="scored" value={scored.length} />
          <Stat label="local 70+" value={scored.filter((j) => j.score >= 70).length} />
          {claudeScored.length > 0 && <Stat label="Claude 70+" value={claudeScored.filter((j) => j.claudeScore >= 70).length} />}
          <Stat label="companies" value={new Set(jobs.map((j) => j.company)).size} />
        </div>
      </header>
      <Tabs tab={tab} company={company} />
      {tab === 'top' && <SortLinks sort={sort} />}
      {jobs.length === 0
        ? tab === 'top' && sort === 'claude'
          ? <div className="empty"><h2>No Claude scores yet</h2><p>Run <b>Claude score</b> from the <a href="?tab=runs">Runs</a> tab.</p></div>
          : <div className="empty"><h2>No jobs yet</h2><p>Start a full run from the <a href="?tab=runs">Runs</a> tab.</p></div>
        : <section className="list">{jobs.map((j) => <JobCard key={j.id} j={j} />)}</section>}
    </main>
  )
}
