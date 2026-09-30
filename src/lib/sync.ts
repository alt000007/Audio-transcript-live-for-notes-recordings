import { db, transcriptOf } from './db'
import { supabase } from './supabase'

/**
 * Pushes local sessions to Supabase. Deliberately one-way and text-only:
 * audio never leaves the device except as a transient segment upload, and the
 * local copy stays authoritative so the app works identically offline.
 */
export async function pushDirty(): Promise<number> {
  if (!supabase) return 0
  const { data: auth } = await supabase.auth.getSession()
  const userId = auth.session?.user.id
  if (!userId) return 0

  // IndexedDB cannot index booleans, so `dirty` is filtered in memory. The
  // library is small enough (hundreds of rows at most) that this is free.
  const all = await db.sessions.toArray()
  const pending = all.filter((s) => s.dirty && s.status !== 'recording')
  let pushed = 0

  for (const s of pending) {
    const transcript = await transcriptOf(s.id)
    const row = {
      id: s.remoteId ?? s.id,
      user_id: userId,
      title: s.title,
      created_at: new Date(s.createdAt).toISOString(),
      updated_at: new Date(s.updatedAt).toISOString(),
      duration_ms: s.durationMs,
      language: s.language,
      transcript,
      notes: s.notes,
    }
    const { error } = await supabase.from('sessions').upsert(row, { onConflict: 'id' })
    if (error) continue
    await db.sessions.update(s.id, { remoteId: row.id, dirty: false })
    pushed++
  }
  return pushed
}

/** Pulls sessions recorded on other devices into the local library. */
export async function pullRemote(): Promise<number> {
  if (!supabase) return 0
  const { data: auth } = await supabase.auth.getSession()
  if (!auth.session) return 0

  const { data, error } = await supabase
    .from('sessions')
    .select('id,title,created_at,updated_at,duration_ms,language,transcript,notes')
    .order('created_at', { ascending: false })
    .limit(200)
  if (error || !data) return 0

  let added = 0
  for (const r of data) {
    const existing = await db.sessions.get(r.id)
    if (existing) continue
    await db.sessions.add({
      id: r.id,
      title: r.title,
      createdAt: new Date(r.created_at).getTime(),
      updatedAt: new Date(r.updated_at).getTime(),
      durationMs: r.duration_ms ?? 0,
      status: 'ready',
      language: r.language ?? '',
      notes: r.notes ?? null,
      remoteId: r.id,
      dirty: false,
    })
    // Remote sessions arrive as one block of text; store it as a single segment
    // so the transcript view has something to render.
    if (r.transcript) {
      await db.segments.add({
        id: crypto.randomUUID(),
        sessionId: r.id,
        index: 0,
        startMs: 0,
        endMs: r.duration_ms ?? 0,
        blob: null,
        bytes: 0,
        text: r.transcript,
        status: 'done',
        attempts: 0,
        error: null,
      })
    }
    added++
  }
  return added
}
