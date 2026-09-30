import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useLiveQuery } from 'dexie-react-hooks'
import { db, deleteSession, getSettings, timedTranscriptOf, transcriptOf } from '../lib/db'
import { queue } from '../lib/queue'
import { structureTranscript } from '../lib/structure'
import { pushDirty } from '../lib/sync'
import { LiveTranscript } from '../components/LiveTranscript'
import { NotesView } from '../components/NotesView'
import { Banner } from '../components/Banner'
import { CopyIcon, DownloadIcon, SparkIcon } from '../components/Icons'
import { download, fmtTime, notesToMarkdown, safeFilename } from '../lib/export'

export function SessionView() {
  const { id = '' } = useParams()
  const nav = useNavigate()
  const [tab, setTab] = useState<'notes' | 'transcript'>('notes')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)
  const [renaming, setRenaming] = useState(false)

  const session = useLiveQuery(() => db.sessions.get(id), [id])
  const stats = useLiveQuery(async () => {
    const segs = await db.segments.where('sessionId').equals(id).toArray()
    return {
      total: segs.length,
      done: segs.filter((s) => s.status === 'done').length,
      pending: segs.filter((s) => s.status === 'pending' || s.status === 'uploading').length,
      failed: segs.filter((s) => s.status === 'failed').length,
      words: segs.reduce((n, s) => n + (s.text ? s.text.trim().split(/\s+/).length : 0), 0),
    }
  }, [id], { total: 0, done: 0, pending: 0, failed: 0, words: 0 })

  if (session === undefined) return <p className="faint">Loading…</p>
  if (session === null) return <div className="empty"><h3>Recording not found</h3></div>

  const pending = stats?.pending ?? 0
  const canStructure = (stats?.done ?? 0) > 0 && pending === 0

  async function generateNotes() {
    setBusy(true)
    setError(null)
    try {
      const [chunks, settings] = await Promise.all([timedTranscriptOf(id), getSettings()])
      const notes = await structureTranscript(chunks, session!.durationMs, settings)
      await db.sessions.update(id, { notes, status: 'ready', updatedAt: Date.now(), dirty: true })
      setTab('notes')
      void pushDirty()
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  async function exportMd() {
    const transcript = await transcriptOf(id)
    const md = notesToMarkdown(session!, session!.notes, transcript)
    download(`${safeFilename(session!.title)}.md`, md)
  }

  async function copyAll() {
    const transcript = await transcriptOf(id)
    const md = notesToMarkdown(session!, session!.notes, transcript)
    await navigator.clipboard.writeText(md)
    setCopied(true)
    setTimeout(() => setCopied(false), 1800)
  }

  async function remove() {
    if (!confirm(`Delete “${session!.title}”? This cannot be undone.`)) return
    queue.cancelSession(id)
    await deleteSession(id)
    nav('/library')
  }

  async function rename(next: string) {
    setRenaming(false)
    const t = next.trim()
    if (!t || t === session!.title) return
    await db.sessions.update(id, { title: t, updatedAt: Date.now(), dirty: true })
    void pushDirty()
  }

  return (
    <>
      {error && <Banner kind="error" onDismiss={() => setError(null)}>{error}</Banner>}

      <div className="card">
        {renaming ? (
          <input
            className="input"
            autoFocus
            defaultValue={session.title}
            onBlur={(e) => void rename(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') (e.target as HTMLInputElement).blur()
              if (e.key === 'Escape') setRenaming(false)
            }}
          />
        ) : (
          <h2
            style={{ margin: 0, fontSize: 19, letterSpacing: '-0.01em', cursor: 'text' }}
            onClick={() => setRenaming(true)}
            title="Click to rename"
          >
            {session.title}
          </h2>
        )}

        <div className="row wrap tiny faint" style={{ gap: 8, marginTop: 6 }}>
          <span>{new Date(session.createdAt).toLocaleString()}</span>
          <span>·</span>
          <span className="mono">{fmtTime(session.durationMs)}</span>
          {(stats?.words ?? 0) > 0 && (
            <>
              <span>·</span>
              <span>{stats!.words.toLocaleString()} words</span>
            </>
          )}
        </div>

        {pending > 0 && (
          <div className="row small muted" style={{ marginTop: 12, gap: 8 }}>
            <span className="spinner" />
            Transcribing {pending} remaining {pending === 1 ? 'part' : 'parts'}…
          </div>
        )}

        {(stats?.failed ?? 0) > 0 && (
          <div className="row small" style={{ marginTop: 12, gap: 8 }}>
            <span className="pill warn">{stats!.failed} failed</span>
            <button className="btn ghost sm" onClick={() => void queue.retryFailed(id)}>
              Retry them
            </button>
          </div>
        )}

        <div className="row wrap" style={{ marginTop: 14, gap: 8 }}>
          <button className="btn primary" onClick={() => void generateNotes()} disabled={busy || !canStructure}>
            {busy ? <span className="spinner" /> : <SparkIcon />}
            {session.notes ? 'Regenerate notes' : 'Generate notes'}
          </button>
          <button className="btn" onClick={() => void exportMd()}>
            <DownloadIcon /> Markdown
          </button>
          <button className="btn" onClick={() => void copyAll()}>
            <CopyIcon /> {copied ? 'Copied' : 'Copy'}
          </button>
          <button className="btn danger sm" onClick={() => void remove()} style={{ marginLeft: 'auto' }}>
            Delete
          </button>
        </div>

        {!canStructure && pending === 0 && (stats?.done ?? 0) === 0 && (
          <p className="tiny faint" style={{ marginTop: 10, marginBottom: 0 }}>
            Nothing was transcribed, so there is nothing to turn into notes.
          </p>
        )}
      </div>

      <div className="row spread" style={{ margin: '18px 0 12px' }}>
        <div className="segmented" role="group" aria-label="View">
          <button aria-pressed={tab === 'notes'} onClick={() => setTab('notes')}>
            Notes
          </button>
          <button aria-pressed={tab === 'transcript'} onClick={() => setTab('transcript')}>
            Transcript
          </button>
        </div>
      </div>

      {tab === 'notes' ? (
        session.notes ? (
          <NotesView notes={session.notes} />
        ) : (
          <div className="empty">
            <h3>No notes yet</h3>
            <p>
              Generate notes to group this recording into topics. The raw transcript always stays untouched underneath.
            </p>
          </div>
        )
      ) : (
        <LiveTranscript sessionId={id} />
      )}
    </>
  )
}
