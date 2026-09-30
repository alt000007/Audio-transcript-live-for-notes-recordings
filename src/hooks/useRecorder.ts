import { useCallback, useEffect, useRef, useState } from 'react'
import { SegmentingRecorder } from '../lib/audio/recorder'
import { db, getSettings } from '../lib/db'
import { queue } from '../lib/queue'
import type { Session } from '../lib/types'

export type RecorderState = 'idle' | 'starting' | 'recording' | 'paused' | 'stopping'

export interface UseRecorder {
  state: RecorderState
  sessionId: string | null
  elapsedMs: number
  level: number
  speaking: boolean
  error: string | null
  start: (title: string, deviceId?: string) => Promise<string | null>
  pause: () => void
  resume: () => void
  stop: () => Promise<string | null>
  dismissError: () => void
}

function newId() {
  return crypto.randomUUID()
}

export function useRecorder(): UseRecorder {
  const [state, setState] = useState<RecorderState>('idle')
  const [sessionId, setSessionId] = useState<string | null>(null)
  const [elapsedMs, setElapsed] = useState(0)
  const [level, setLevel] = useState(0)
  const [speaking, setSpeaking] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const recRef = useRef<SegmentingRecorder | null>(null)
  const sessionRef = useRef<string | null>(null)
  const startedAt = useRef(0)
  const pausedTotal = useRef(0)
  const pausedAt = useRef(0)
  const wake = useRef<WakeLockSentinel | null>(null)

  // A screen lock stops the tick, so we show elapsed time from the clock rather
  // than counting intervals.
  useEffect(() => {
    if (state !== 'recording') return
    const id = window.setInterval(() => {
      setElapsed(Date.now() - startedAt.current - pausedTotal.current)
    }, 250)
    return () => clearInterval(id)
  }, [state])

  const releaseWakeLock = useCallback(() => {
    void wake.current?.release().catch(() => {})
    wake.current = null
  }, [])

  const start = useCallback(async (title: string, deviceId?: string) => {
    setError(null)
    setState('starting')
    try {
      const settings = await getSettings()
      const id = newId()
      const now = Date.now()
      const session: Session = {
        id,
        title: title.trim() || defaultTitle(),
        createdAt: now,
        updatedAt: now,
        durationMs: 0,
        status: 'recording',
        language: settings.language,
        notes: null,
        remoteId: null,
        dirty: true,
      }
      await db.sessions.add(session)

      const rec = new SegmentingRecorder(
        {
          deviceId,
          minSegmentMs: settings.minSegmentMs,
          maxSegmentMs: settings.maxSegmentMs,
          silenceHoldMs: settings.silenceHoldMs,
        },
        {
          onSegment: (seg) => {
            void db.segments
              .add({
                id: newId(),
                sessionId: id,
                index: seg.index,
                startMs: seg.startMs,
                endMs: seg.endMs,
                blob: seg.blob,
                bytes: seg.blob.size,
                text: '',
                status: 'pending',
                attempts: 0,
                error: null,
              })
              .then(() => queue.wake())
          },
          onMeter: (lvl, spk) => {
            setLevel(lvl)
            setSpeaking(spk)
          },
          onError: (e) => setError(e.message),
        },
      )

      await rec.start()
      recRef.current = rec
      sessionRef.current = id
      startedAt.current = Date.now()
      pausedTotal.current = 0
      setSessionId(id)
      setElapsed(0)
      setState('recording')

      // Keep the screen awake so the browser does not throttle the recorder.
      try {
        wake.current = await navigator.wakeLock?.request('screen')
      } catch {
        // Unsupported or denied — recording still works, the screen may sleep.
      }
      return id
    } catch (err) {
      setState('idle')
      setError(friendlyMicError(err))
      // Roll back the row so the library does not fill with empty sessions.
      if (sessionRef.current) {
        await db.sessions.delete(sessionRef.current).catch(() => {})
        sessionRef.current = null
      }
      return null
    }
  }, [])

  const pause = useCallback(() => {
    if (!recRef.current || state !== 'recording') return
    recRef.current.pause()
    pausedAt.current = Date.now()
    setState('paused')
    setLevel(0)
    setSpeaking(false)
  }, [state])

  const resume = useCallback(() => {
    if (!recRef.current || state !== 'paused') return
    pausedTotal.current += Date.now() - pausedAt.current
    recRef.current.resume()
    setState('recording')
  }, [state])

  const stop = useCallback(async () => {
    const id = sessionRef.current
    if (!recRef.current || !id) return null
    setState('stopping')
    await recRef.current.stop()
    recRef.current = null
    releaseWakeLock()

    const duration = Date.now() - startedAt.current - pausedTotal.current
    await db.sessions.update(id, { durationMs: duration, status: 'stopped', updatedAt: Date.now(), dirty: true })

    sessionRef.current = null
    setSessionId(null)
    setState('idle')
    setLevel(0)
    setSpeaking(false)
    void queue.wake()
    return id
  }, [releaseWakeLock])

  // Never leave the microphone open if the component unmounts.
  useEffect(() => {
    return () => {
      void recRef.current?.stop()
      releaseWakeLock()
    }
  }, [releaseWakeLock])

  // Warn before a tab close takes the recording with it.
  useEffect(() => {
    if (state !== 'recording' && state !== 'paused') return
    const handler = (e: BeforeUnloadEvent) => e.preventDefault()
    window.addEventListener('beforeunload', handler)
    return () => window.removeEventListener('beforeunload', handler)
  }, [state])

  return {
    state,
    sessionId,
    elapsedMs,
    level,
    speaking,
    error,
    start,
    pause,
    resume,
    stop,
    dismissError: () => setError(null),
  }
}

function defaultTitle() {
  const d = new Date()
  return `Recording — ${d.toLocaleDateString()} ${d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}`
}

function friendlyMicError(err: unknown): string {
  const e = err as DOMException
  switch (e?.name) {
    case 'NotAllowedError':
      return 'Microphone access was blocked. Allow it in your browser’s site settings and try again.'
    case 'NotFoundError':
      return 'No microphone found. Plug one in or pick a different input.'
    case 'NotReadableError':
      return 'The microphone is in use by another app. Close it and try again.'
    default:
      return e?.message || 'Could not start recording.'
  }
}
