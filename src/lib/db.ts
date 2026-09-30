import Dexie, { type Table } from 'dexie'
import type { Session, Segment, Settings } from './types'
import { DEFAULT_SETTINGS } from './types'

/**
 * Everything is written here first and synced to Supabase opportunistically.
 * A lecture hall with dead wifi must not cost you the lecture.
 */
class ScribeDb extends Dexie {
  sessions!: Table<Session, string>
  segments!: Table<Segment, string>
  kv!: Table<{ key: string; value: unknown }, string>

  constructor() {
    super('scribe')
    this.version(1).stores({
      sessions: 'id, createdAt, status',
      segments: 'id, sessionId, [sessionId+index], status',
      kv: 'key',
    })
  }
}

export const db = new ScribeDb()

export async function getSettings(): Promise<Settings> {
  const row = await db.kv.get('settings')
  return { ...DEFAULT_SETTINGS, ...((row?.value as Partial<Settings>) ?? {}) }
}

export async function saveSettings(patch: Partial<Settings>): Promise<Settings> {
  const next = { ...(await getSettings()), ...patch }
  await db.kv.put({ key: 'settings', value: next })
  return next
}

/** Full transcript text for a session, in order, only counting transcribed segments. */
export async function transcriptOf(sessionId: string): Promise<string> {
  const segs = await db.segments.where('sessionId').equals(sessionId).sortBy('index')
  return segs
    .filter((s) => s.status === 'done' && s.text.trim())
    .map((s) => s.text.trim())
    .join(' ')
}

/** Transcript with timestamps, used to anchor topic headings to the audio. */
export async function timedTranscriptOf(sessionId: string): Promise<{ startMs: number; text: string }[]> {
  const segs = await db.segments.where('sessionId').equals(sessionId).sortBy('index')
  return segs
    .filter((s) => s.status === 'done' && s.text.trim())
    .map((s) => ({ startMs: s.startMs, text: s.text.trim() }))
}

export async function deleteSession(sessionId: string) {
  await db.transaction('rw', db.sessions, db.segments, async () => {
    await db.segments.where('sessionId').equals(sessionId).delete()
    await db.sessions.delete(sessionId)
  })
}

/** Rough bytes held locally, so Settings can show what audio retention costs. */
export async function storageUsed(): Promise<number> {
  let total = 0
  await db.segments.each((s) => {
    if (s.blob) total += s.bytes
  })
  return total
}
