import type { Notes } from '../lib/types'
import { fmtTime } from '../lib/export'

export function NotesView({ notes }: { notes: Notes }) {
  return (
    <div>
      {notes.summary && (
        <div className="card" style={{ marginBottom: 18 }}>
          <div className="tiny faint" style={{ textTransform: 'uppercase', letterSpacing: '0.06em', marginBottom: 6 }}>
            Summary
          </div>
          <div>{notes.summary}</div>
        </div>
      )}

      {notes.topics.map((t, i) => (
        <section className="topic" key={`${t.heading}-${i}`}>
          <h3>{t.heading}</h3>
          <div className="tiny faint mono">{fmtTime(t.startMs)}</div>
          <ul>
            {t.bullets.map((b, j) => (
              <li key={j}>{b}</li>
            ))}
          </ul>
        </section>
      ))}

      {notes.corrections.length > 0 && (
        <details className="card">
          <summary style={{ cursor: 'pointer', fontWeight: 600, fontSize: 14 }}>
            {notes.corrections.length} term{notes.corrections.length === 1 ? '' : 's'} corrected
          </summary>
          <p className="tiny faint" style={{ marginTop: 8 }}>
            Words the model believes the recogniser misheard. Check these against the raw transcript if something looks
            wrong.
          </p>
          <ul className="small" style={{ marginBottom: 0 }}>
            {notes.corrections.map((c, i) => (
              <li key={i}>
                <span className="faint">“{c.from}”</span> → <strong>{c.to}</strong>
              </li>
            ))}
          </ul>
        </details>
      )}
    </div>
  )
}
