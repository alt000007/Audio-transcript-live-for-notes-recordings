import { Link } from 'react-router-dom'
import { useLiveQuery } from 'dexie-react-hooks'
import { db } from '../lib/db'
import { fmtTime } from '../lib/export'

type SegmentCounts = Record<string, { pending: number; failed: number }>

function relative(ts: number): string {
  const diff = Date.now() - ts
  const day = 86_400_000
  if (diff < 60_000) return 'just now'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m ago`
  if (diff < day) return `${Math.floor(diff / 3_600_000)}h ago`
  if (diff < 7 * day) return `${Math.floor(diff / day)}d ago`
  return new Date(ts).toLocaleDateString()
}

export function Library() {
  const sessions = useLiveQuery(() => db.sessions.orderBy('createdAt').reverse().toArray(), [], [])
  const counts = useLiveQuery(async () => {
    const map: SegmentCounts = {}
    await db.segments.each((s) => {
      const e = (map[s.sessionId] ??= { pending: 0, failed: 0 })
      if (s.status === 'pending' || s.status === 'uploading') e.pending++
      if (s.status === 'failed') e.failed++
    })
    return map
  }, [sessions], {} as SegmentCounts)

  if (!sessions || sessions.length === 0) {
    return (
      <div className="empty">
        <h3>No recordings yet</h3>
        <p>Start one from the Record tab. Everything is saved on this device first, so it works without a signal.</p>
      </div>
    )
  }

  return (
    <div className="list">
      {sessions.map((s) => {
        const c = counts?.[s.id]
        return (
          <Link className="sessionrow" to={`/s/${s.id}`} key={s.id}>
            <h3>{s.title}</h3>
            <div className="row wrap tiny faint" style={{ gap: 8 }}>
              <span>{relative(s.createdAt)}</span>
              <span>·</span>
              <span className="mono">{fmtTime(s.durationMs)}</span>
              {s.notes && <span className="pill ok">Notes</span>}
              {c && c.pending > 0 && <span className="pill">{c.pending} transcribing</span>}
              {c && c.failed > 0 && <span className="pill warn">{c.failed} failed</span>}
            </div>
          </Link>
        )
      })}
    </div>
  )
}
