import { useEffect, useState } from 'react'
import type { Session as AuthSession } from '@supabase/supabase-js'
import { supabase, hasBackend } from '../lib/supabase'

export interface Auth {
  ready: boolean
  session: AuthSession | null
  email: string | null
  /** True when there is no backend at all — everything stays on this device. */
  guestOnly: boolean
}

export function useAuth(): Auth {
  const [session, setSession] = useState<AuthSession | null>(null)
  const [ready, setReady] = useState(!hasBackend)

  useEffect(() => {
    if (!supabase) return
    let alive = true
    void supabase.auth.getSession().then(({ data }) => {
      if (!alive) return
      setSession(data.session)
      setReady(true)
    })
    const { data: sub } = supabase.auth.onAuthStateChange((_e, s) => setSession(s))
    return () => {
      alive = false
      sub.subscription.unsubscribe()
    }
  }, [])

  return {
    ready,
    session,
    email: session?.user.email ?? null,
    guestOnly: !hasBackend,
  }
}

export async function signInWithEmail(email: string) {
  if (!supabase) throw new Error('No backend configured.')
  const { error } = await supabase.auth.signInWithOtp({
    email,
    options: { emailRedirectTo: window.location.origin },
  })
  if (error) throw error
}

export async function signOut() {
  await supabase?.auth.signOut()
}
