import { useEffect, useState } from 'react'
import { getSettings, saveSettings, storageUsed, db } from '../lib/db'
import { DEFAULT_SETTINGS, type Settings } from '../lib/types'
import { Banner } from '../components/Banner'
import { hasBackend } from '../lib/supabase'
import { signOut, useAuth } from '../hooks/useAuth'
import { pullRemote, pushDirty } from '../lib/sync'

const LANGUAGES = [
  ['', 'Detect automatically'],
  ['en', 'English'],
  ['pt', 'Portuguese'],
  ['es', 'Spanish'],
  ['fr', 'French'],
  ['de', 'German'],
  ['it', 'Italian'],
  ['nl', 'Dutch'],
  ['pl', 'Polish'],
  ['ja', 'Japanese'],
  ['zh', 'Chinese'],
]

function mb(bytes: number) {
  return `${(bytes / 1_048_576).toFixed(1)} MB`
}

export function SettingsPage() {
  const auth = useAuth()
  const [s, setS] = useState<Settings | null>(null)
  const [used, setUsed] = useState(0)
  const [msg, setMsg] = useState<string | null>(null)

  useEffect(() => {
    void getSettings().then(setS)
    void storageUsed().then(setUsed)
  }, [])

  if (!s) return <p className="faint">Loading…</p>

  async function patch(p: Partial<Settings>) {
    setS(await saveSettings(p))
  }

  async function clearAudio() {
    await db.segments.where('status').equals('done').modify({ blob: null })
    setUsed(await storageUsed())
    setMsg('Stored audio cleared. Transcripts and notes are unaffected.')
  }

  async function syncNow() {
    const [pushed, pulled] = [await pushDirty(), await pullRemote()]
    setMsg(`Synced — ${pushed} uploaded, ${pulled} downloaded.`)
  }

  const hoursPerGb = s.keepAudio ? Math.round(1_073_741_824 / (20_000 / 8) / 3600) : 0

  return (
    <div className="stack">
      {msg && <Banner onDismiss={() => setMsg(null)}>{msg}</Banner>}

      <div className="card stack">
        <h2 style={{ margin: 0, fontSize: 16 }}>Transcription</h2>

        <div className="field">
          <label htmlFor="engine">Engine</label>
          <select
            id="engine"
            className="select"
            value={s.engine}
            onChange={(e) => void patch({ engine: e.target.value as Settings['engine'] })}
          >
            <option value="groq" disabled={!hasBackend}>
              Whisper via shared key{hasBackend ? '' : ' (needs a backend)'}
            </option>
            <option value="groq-byok">Whisper via my own Groq key</option>
          </select>
          <div className="hint">
            Both run <span className="mono">whisper-large-v3-turbo</span>. Groq&rsquo;s free tier covers normal class
            use at no cost.
          </div>
        </div>

        {s.engine === 'groq-byok' && (
          <div className="field">
            <label htmlFor="key">Groq API key</label>
            <input
              id="key"
              className="input"
              type="password"
              autoComplete="off"
              value={s.groqKey}
              placeholder="gsk_…"
              onChange={(e) => void patch({ groqKey: e.target.value })}
            />
            <div className="hint">
              Free at <span className="mono">console.groq.com</span>. Stored only in this browser and sent straight to
              Groq — it never reaches any server of ours.
            </div>
          </div>
        )}

        <div className="field">
          <label htmlFor="lang">Language</label>
          <select id="lang" className="select" value={s.language} onChange={(e) => void patch({ language: e.target.value })}>
            {LANGUAGES.map(([v, l]) => (
              <option key={v} value={v}>
                {l}
              </option>
            ))}
          </select>
          <div className="hint">Naming the language is more accurate than autodetect on short, noisy segments.</div>
        </div>

        <div className="field">
          <label htmlFor="glossary">Glossary</label>
          <textarea
            id="glossary"
            className="textarea"
            value={s.glossary}
            placeholder={'mitochondria\nKrebs cycle\nDr. Okonkwo'}
            onChange={(e) => void patch({ glossary: e.target.value })}
          />
          <div className="hint">
            Course jargon, lecturer names, anything the recogniser keeps mangling — one per line. These are fed to
            Whisper as a hint while it decodes, which fixes far more than correcting the text afterwards.
          </div>
        </div>
      </div>

      <div className="card stack">
        <h2 style={{ margin: 0, fontSize: 16 }}>Segmenting</h2>
        <p className="small muted" style={{ margin: 0 }}>
          Audio is cut at natural pauses. Shorter segments mean text appears sooner; longer segments give Whisper more
          context and transcribe more accurately.
        </p>

        <div className="field">
          <label htmlFor="min">Shortest segment — {(s.minSegmentMs / 1000).toFixed(1)}s</label>
          <input
            id="min"
            type="range"
            min={2000}
            max={8000}
            step={500}
            value={s.minSegmentMs}
            onChange={(e) => void patch({ minSegmentMs: Number(e.target.value) })}
            style={{ width: '100%' }}
          />
        </div>

        <div className="field">
          <label htmlFor="max">Longest segment — {(s.maxSegmentMs / 1000).toFixed(0)}s</label>
          <input
            id="max"
            type="range"
            min={8000}
            max={30000}
            step={1000}
            value={s.maxSegmentMs}
            onChange={(e) => void patch({ maxSegmentMs: Number(e.target.value) })}
            style={{ width: '100%' }}
          />
          <div className="hint">A hard cap for speakers who never pause.</div>
        </div>
      </div>

      <div className="card stack">
        <h2 style={{ margin: 0, fontSize: 16 }}>Storage</h2>
        <label className="row" style={{ gap: 10, cursor: 'pointer' }}>
          <input
            type="checkbox"
            checked={s.keepAudio}
            onChange={(e) => void patch({ keepAudio: e.target.checked })}
          />
          <span className="grow">
            Keep audio after transcribing
            <div className="tiny faint">
              {s.keepAudio
                ? `Lets you re-transcribe later. Roughly ${hoursPerGb} hours per GB on this device.`
                : 'Audio is discarded once its text comes back. Recommended.'}
            </div>
          </span>
        </label>
        <div className="row spread small">
          <span className="muted">Audio held locally: {mb(used)}</span>
          <button className="btn sm" onClick={() => void clearAudio()} disabled={used === 0}>
            Clear
          </button>
        </div>
      </div>

      <div className="card stack">
        <h2 style={{ margin: 0, fontSize: 16 }}>Account</h2>
        {!hasBackend ? (
          <p className="small muted" style={{ margin: 0 }}>
            Running without a backend — everything stays on this device. Add Supabase credentials to sync across
            devices.
          </p>
        ) : auth.session ? (
          <>
            <div className="row spread">
              <span className="small">{auth.email}</span>
              <button className="btn sm" onClick={() => void signOut()}>
                Sign out
              </button>
            </div>
            <button className="btn block" onClick={() => void syncNow()}>
              Sync now
            </button>
          </>
        ) : (
          <p className="small muted" style={{ margin: 0 }}>
            Not signed in. Recordings stay on this device only.
          </p>
        )}
      </div>

      <button
        className="btn ghost block small"
        onClick={() => {
          if (confirm('Reset all settings to their defaults?')) void patch(DEFAULT_SETTINGS)
        }}
      >
        Reset settings
      </button>
    </div>
  )
}
