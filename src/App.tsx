import { useEffect, useState } from 'react'
import { NavLink, Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom'
import { Record } from './pages/Record'
import { Library } from './pages/Library'
import { SessionView } from './pages/SessionView'
import { SettingsPage } from './pages/SettingsPage'
import { Login } from './pages/Login'
import { useAuth } from './hooks/useAuth'
import { hasBackend } from './lib/supabase'
import { resumeInterrupted } from './lib/queue'
import { pullRemote, pushDirty } from './lib/sync'
import { BackIcon, GearIcon, LibraryIcon, MicIcon } from './components/Icons'

const GUEST_KEY = 'scribe.guest'

export default function App() {
  const auth = useAuth()
  const loc = useLocation()
  const nav = useNavigate()
  const [guest, setGuest] = useState(() => localStorage.getItem(GUEST_KEY) === '1')

  // Pick up anything a crash or a closed tab left half-transcribed.
  useEffect(() => {
    void resumeInterrupted()
  }, [])

  // Sync once signed in, then whenever the tab comes back to the foreground.
  useEffect(() => {
    if (!auth.session) return
    const sync = () => {
      void pushDirty()
      void pullRemote()
    }
    sync()
    const onVis = () => document.visibilityState === 'visible' && sync()
    document.addEventListener('visibilitychange', onVis)
    return () => document.removeEventListener('visibilitychange', onVis)
  }, [auth.session])

  if (!auth.ready) return null

  const needsLogin = hasBackend && !auth.session && !guest
  if (needsLogin) {
    return (
      <Login
        onSkip={() => {
          localStorage.setItem(GUEST_KEY, '1')
          setGuest(true)
        }}
      />
    )
  }

  const onSession = loc.pathname.startsWith('/s/')
  const title = onSession ? '' : loc.pathname.startsWith('/library') ? 'Library' : loc.pathname.startsWith('/settings') ? 'Settings' : 'Record'

  return (
    <div className="app">
      <header className="topbar">
        {onSession && (
          <button className="btn ghost sm" onClick={() => nav(-1)} aria-label="Back">
            <BackIcon />
          </button>
        )}
        {/* The session page carries its own (editable) title in the card below. */}
        <h1>{title}</h1>
      </header>

      <main className="main">
        <Routes>
          <Route path="/" element={<Record />} />
          <Route path="/library" element={<Library />} />
          <Route path="/s/:id" element={<SessionView />} />
          <Route path="/settings" element={<SettingsPage />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>

      <nav className="tabbar">
        <NavLink to="/" end>
          <MicIcon /> Record
        </NavLink>
        <NavLink to="/library">
          <LibraryIcon /> Library
        </NavLink>
        <NavLink to="/settings">
          <GearIcon /> Settings
        </NavLink>
      </nav>
    </div>
  )
}
