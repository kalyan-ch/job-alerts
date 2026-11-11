// Proxies Spring's run events, so the browser only ever talks to Next.
export const dynamic = 'force-dynamic'

const API = process.env.JOBS_API ?? 'http://localhost:8080'

export async function GET(req) {
  let r
  try {
    r = await fetch(`${API}/api/runs/events`, { cache: 'no-store', signal: req.signal })
  } catch (e) {
    // Browser closed the EventSource (reload, navigation, HMR) before Spring answered
    if (req.signal.aborted) return new Response(null, { status: 499 })
    throw e
  }
  return new Response(r.body, {
    status: r.status,
    // no-transform: keeps compression from buffering the stream
    headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache, no-transform' },
  })
}
