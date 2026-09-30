import { useState } from 'react'
import { signInWithEmail } from '../hooks/useAuth'
import { Banner } from '../components/Banner'
import { MicIcon } from '../components/Icons'

export function Login({ onSkip }: { onSkip: () => void }) {
  const [email, setEmail] = useState('')
  const [sent, setSent] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await signInWithEmail(email.trim())
      setSent(true)
    } catch (err) {
      setError((err as Error).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div style={{ maxWidth: 380, margin: '0 auto', padding: '12vh 20px 40px' }}>
      <div style={{ textAlign: 'center', marginBottom: 28 }}>
        <div
          style={{
            width: 54,
            height: 54,
            margin: '0 auto 14px',
            borderRadius: 16,
            background: 'var(--accent-soft)',
            color: 'var(--accent)',
            display: 'grid',
            placeItems: 'center',
          }}
        >
          <MicIcon className="" />
        </div>
        <h1 style={{ margin: 0, fontSize: 24, letterSpacing: '-0.02em' }}>Scribe</h1>
        <p className="muted small" style={{ marginTop: 6 }}>
          Record a class, get a transcript as it happens, turn it into notes.
        </p>
      </div>

      {error && <Banner kind="error" onDismiss={() => setError(null)}>{error}</Banner>}

      {sent ? (
        <Banner kind="info">
          Check <strong>{email}</strong> for a sign-in link. You can close this tab.
        </Banner>
      ) : (
        <form onSubmit={submit} className="stack">
          <div className="field">
            <label htmlFor="email">Email</label>
            <input
              id="email"
              className="input"
              type="email"
              required
              autoComplete="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="you@example.com"
            />
            <div className="hint">We send a one-time link. No password to remember.</div>
          </div>
          <button className="btn primary block" type="submit" disabled={busy}>
            {busy ? <span className="spinner" /> : null}
            Send sign-in link
          </button>
        </form>
      )}

      <button className="btn ghost block small" style={{ marginTop: 16 }} onClick={onSkip}>
        Use without an account
      </button>
      <p className="tiny faint" style={{ textAlign: 'center', marginTop: 8 }}>
        Recordings stay on this device and will not sync.
      </p>
    </div>
  )
}
