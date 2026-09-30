// Supabase Edge Function (Deno).
//
// Proxies the notes-structuring call to Groq's chat API. Same reasoning as
// `transcribe`: the key stays server-side and only signed-in users get through.
//   supabase functions deploy structure

import { createClient } from 'jsr:@supabase/supabase-js@2'

const GROQ_URL = 'https://api.groq.com/openai/v1/chat/completions'
const ALLOWED_MODELS = new Set(['llama-3.3-70b-versatile', 'llama-3.1-8b-instant'])
/** A three-hour lecture transcript is still well under this. */
const MAX_CHARS = 400_000

const cors = {
  'Access-Control-Allow-Origin': Deno.env.get('ALLOWED_ORIGIN') ?? '*',
  'Access-Control-Allow-Headers': 'authorization, content-type',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...cors, 'Content-Type': 'application/json' },
  })
}

Deno.serve(async (req) => {
  if (req.method === 'OPTIONS') return new Response(null, { headers: cors })
  if (req.method !== 'POST') return json({ error: 'Method not allowed' }, 405)

  const key = Deno.env.get('GROQ_API_KEY')
  if (!key) return json({ error: 'GROQ_API_KEY is not set on this project.' }, 500)

  const authHeader = req.headers.get('Authorization') ?? ''
  const supabase = createClient(
    Deno.env.get('SUPABASE_URL')!,
    Deno.env.get('SUPABASE_ANON_KEY')!,
    { global: { headers: { Authorization: authHeader } } },
  )
  const { data: userData, error: authError } = await supabase.auth.getUser()
  if (authError || !userData.user) return json({ error: 'Not signed in.' }, 401)

  let body: { model?: string; messages?: unknown; temperature?: number; response_format?: unknown }
  try {
    body = await req.json()
  } catch {
    return json({ error: 'Expected JSON.' }, 400)
  }

  if (!body.model || !ALLOWED_MODELS.has(body.model)) return json({ error: 'Unsupported model.' }, 400)
  if (!Array.isArray(body.messages)) return json({ error: 'Missing messages.' }, 400)
  if (JSON.stringify(body.messages).length > MAX_CHARS) return json({ error: 'Transcript too long.' }, 413)

  const res = await fetch(GROQ_URL, {
    method: 'POST',
    headers: { Authorization: `Bearer ${key}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      model: body.model,
      messages: body.messages,
      temperature: typeof body.temperature === 'number' ? Math.min(1, Math.max(0, body.temperature)) : 0.2,
      response_format: body.response_format ?? { type: 'json_object' },
    }),
  })

  const text = await res.text()
  return new Response(text, {
    status: res.status,
    headers: { ...cors, 'Content-Type': 'application/json' },
  })
})
