import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useRecorder } from '../hooks/useRecorder'
import { listMicrophones } from '../lib/audio/recorder'
import { LiveTranscript } from '../components/LiveTranscript'
import { Banner } from '../components/Banner'
import { MicIcon, PauseIcon, PlayIcon, StopIcon } from '../components/Icons'
import { fmtTime } from '../lib/export'
import { getSettings } from '../lib/db'
import { hasBackend } from '../lib/supabase'

export function Record() {
  const rec = useRecorder()
  const nav = useNavigate()
  const [title, setTitle] = useState('')
  const [mics, setMics] = useState<MediaDeviceInfo[]>([])
  const [deviceId, setDeviceId] = useState<string>('')
  const [online, setOnline] = useState(navigator.onLine)
  const [needsKey, setNeedsKey] = useState(false)

  useEffect(() => {
    const on = () => setOnline(true)
    const off = () => setOnline(false)
    window.addEventListener('online', on)
    window.addEventListener('offline', off)
    return () => {
      window.removeEventListener('online', on)
      window.removeEventListener('offline', off)
    }
  }, [])

  useEffect(() => {
    void getSettings().then((s) => {
      // Without a backend and without a personal key there is nothing to send audio to.
      setNeedsKey(!hasBackend && s.engine !== 'groq-byok')
    })
  }, [])

  // Device labels are blank until permission is granted, so refresh the list
  // once recording has started.
  useEffect(() => {
    void listMicrophones().then(setMics).catch(() => {})
  }, [rec.state])

  const recording = rec.state === 'recording'
  const paused = rec.state === 'paused'
  const active = recording || paused

  async function handleStop() {
    const id = await rec.stop()
    if (id) nav(`/s/${id}`)
  }

  return (
    <>
      {rec.error && (
        <Banner kind="error" onDismiss={rec.dismissError}>
          {rec.error}
        </Banner>
      )}

      {needsKey && !active && (
        <Banner kind="warn">
          No transcription backend yet. Add a free Groq API key in <strong>Settings</strong> — recording works without
          one, but nothing will be transcribed.
        </Banner>
      )}

      {!online && active && (
        <Banner kind="warn">
          Offline — still recording. Audio is saved on this device and will transcribe when you reconnect.
        </Banner>
      )}

      <div className="recorder">
        <div className="row" style={{ justifyContent: 'center', gap: 8, minHeight: 22 }}>
          {recording && (
            <>
              <span className="reclight" />
              <span className="small muted">Recording</span>
            </>
          )}
          {paused && <span className="pill warn">Paused</span>}
          {!active && <span className="small faint">Ready</span>}
        </div>

        <div className="timer">{fmtTime(rec.elapsedMs)}</div>

        <div className="meter" aria-hidden="true">
          <div className={rec.speaking ? 'speaking' : ''} style={{ width: `${Math.round(rec.level * 100)}%` }} />
        </div>

        <div className="reccontrols">
          {!active ? (
            <button
              className="bigbtn"
              onClick={() => void rec.start(title, deviceId || undefined)}
              disabled={rec.state === 'starting'}
              aria-label="Start recording"
            >
              <MicIcon />
            </button>
          ) : (
            <>
              <button
                className="btn"
                onClick={() => (paused ? rec.resume() : rec.pause())}
                aria-label={paused ? 'Resume' : 'Pause'}
              >
                <span style={{ width: 16, height: 16, display: 'grid', placeItems: 'center' }}>
                  {paused ? <PlayIcon /> : <PauseIcon />}
                </span>
                {paused ? 'Resume' : 'Pause'}
              </button>
              <button className="bigbtn" onClick={() => void handleStop()} aria-label="Stop recording">
                <StopIcon />
              </button>
            </>
          )}
        </div>
      </div>

      {!active && (
        <div className="card stack" style={{ marginTop: 20 }}>
          <div className="field">
            <label htmlFor="title">Title</label>
            <input
              id="title"
              className="input"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder="e.g. Organic chemistry — week 4"
            />
          </div>
          {mics.length > 1 && (
            <div className="field">
              <label htmlFor="mic">Microphone</label>
              <select id="mic" className="select" value={deviceId} onChange={(e) => setDeviceId(e.target.value)}>
                <option value="">System default</option>
                {mics.map((m) => (
                  <option key={m.deviceId} value={m.deviceId}>
                    {m.label || 'Microphone'}
                  </option>
                ))}
              </select>
            </div>
          )}
        </div>
      )}

      {rec.sessionId && (
        <div style={{ marginTop: 20 }}>
          <LiveTranscript sessionId={rec.sessionId} follow />
        </div>
      )}
    </>
  )
}
