import { createClient, type SupabaseClient } from '@supabase/supabase-js'

const url = import.meta.env.VITE_SUPABASE_URL as string | undefined
const anon = import.meta.env.VITE_SUPABASE_ANON_KEY as string | undefined

/**
 * Null when the app is running without a backend. Every call site must handle
 * that: guest mode is a first-class way to use this app, not a degraded one.
 */
export const supabase: SupabaseClient | null =
  url && anon ? createClient(url, anon, { auth: { persistSession: true, autoRefreshToken: true } }) : null

export const hasBackend = Boolean(supabase)

/** Base URL for the edge functions that hold the Groq key. */
export function functionsUrl(name: string): string | null {
  if (!url) return null
  return `${url.replace(/\/$/, '')}/functions/v1/${name}`
}
