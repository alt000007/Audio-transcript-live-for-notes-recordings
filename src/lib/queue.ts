import { db, getSettings } from './db'
import { buildPrompt, getTranscriber, TranscribeError } from './transcribe'
import type { Segment } from './types'

const MAX_ATTEMPTS = 5
const CONCURRENCY = 2

type Listener = () => void

/**
 * Drains pending segments to the transcription API.
 *
 * Segments live in IndexedDB before they are ever sent, so losing the network
 * mid-lecture only delays the transcript — it never loses the audio. The queue
 * restarts itself when connectivity returns and when the app is reopened.
 */
class TranscriptionQueue {
  private running = 0
  private draining = false
  private listeners = new Set<Listener>()
  /** Sessions whose segments should be skipped (user deleted them mid-flight). */
  private cancelled = new Set<string>()

  subscribe(fn: Listener) {
    this.listeners.add(fn)
    return () => this.listeners.delete(fn)
  }

  private emit() {
    this.listeners.forEach((l) => l())
  }

  cancelSession(sessionId: string) {
    this.cancelled.add(sessionId)
  }

  /** Kick the queue. Safe to call as often as you like. */
  async wake() {
    if (this.draining) return
    this.draining = true
    try {
      // Loop until nothing is left that we can act on right now.
      for (;;) {
        if (!navigator.onLine) break
        const slots = CONCURRENCY - this.running
        if (slots <= 0) {
          await sleep(120)
          continue
        }
        const batch = await db.segments.where('status').equals('pending').limit(slots).toArray()
        if (batch.length === 0) {
          if (this.running > 0) {
            await sleep(120)
            continue
          }
          break
        }
        await Promise.all(batch.map((s) => this.process(s)))
      }
    } finally {
      this.draining = false
    }
  }

  private async process(seg: Segment) {
    if (this.cancelled.has(seg.sessionId)) {
      await db.segments.delete(seg.id)
      return
    }
    // Claim it first so a second drain pass cannot pick up the same segment.
    const claimed = await db.segments
      .where('id')
      .equals(seg.id)
      .modify({ status: 'uploading' })
    if (claimed === 0) return

    this.running++
    this.emit()
    try {
      const settings = await getSettings()
      const transcriber = getTranscriber(settings)

      // Use the preceding segments as Whisper's decoding prior.
      const prior = await db.segments
        .where('[sessionId+index]')
        .between([seg.sessionId, Math.max(0, seg.index - 3)], [seg.sessionId, seg.index], true, false)
        .toArray()
      const recent = prior
        .filter((p) => p.status === 'done')
        .map((p) => p.text)
        .join(' ')

      if (!seg.blob) throw new TranscribeError('Audio for this segment is no longer available.', false)

      const text = await transcriber.transcribe({
        blob: seg.blob,
        mime: seg.blob.type,
        language: settings.language,
        prompt: buildPrompt(settings.glossary, recent),
      })

      await db.segments.update(seg.id, {
        text,
        status: text ? 'done' : 'skipped',
        error: null,
        // Audio has served its purpose unless the user asked to keep it.
        blob: settings.keepAudio ? seg.blob : null,
      })
      await touch(seg.sessionId)
    } catch (err) {
      const e = err as TranscribeError
      const attempts = seg.attempts + 1
      const retryable = e instanceof TranscribeError ? e.retryable : true
      const giveUp = !retryable || attempts >= MAX_ATTEMPTS

      await db.segments.update(seg.id, {
        attempts,
        status: giveUp ? 'failed' : 'pending',
        error: e.message ?? String(err),
      })

      if (!giveUp) {
        // Back off so a rate limit does not become a hot loop: 1s, 2s, 4s, 8s.
        await sleep(Math.min(8000, 1000 * 2 ** (attempts - 1)))
      }
    } finally {
      this.running--
      this.emit()
    }
  }

  /** Put failed segments back in line — used by the "retry" button. */
  async retryFailed(sessionId: string) {
    await db.segments
      .where('sessionId')
      .equals(sessionId)
      .and((s) => s.status === 'failed' && s.blob !== null)
      .modify({ status: 'pending', attempts: 0, error: null })
    void this.wake()
  }
}

async function touch(sessionId: string) {
  await db.sessions.update(sessionId, { updatedAt: Date.now(), dirty: true })
}

function sleep(ms: number) {
  return new Promise((r) => setTimeout(r, ms))
}

export const queue = new TranscriptionQueue()

/** Recover anything left mid-flight by a crash or a closed tab. */
export async function resumeInterrupted() {
  await db.segments.where('status').equals('uploading').modify({ status: 'pending' })
  await db.sessions.where('status').equals('recording').modify({ status: 'stopped' })
  void queue.wake()
}

if (typeof window !== 'undefined') {
  window.addEventListener('online', () => void queue.wake())
}
