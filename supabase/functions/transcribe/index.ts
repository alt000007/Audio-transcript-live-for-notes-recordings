// Supabase Edge Function (Deno).
//
// Holds the Groq API key so the browser never sees it, and refuses anyone who
// is not a signed-in user of this project. Deploy with:
//   supabase secrets set GROQ_API_KEY=gsk_...
//   supabase functions deploy transcribe

import { createClient } from 'jsr:@supabase/supabase-js@2'

const GROQ_URL = 'https://api.groq.com/openai/v1/audio/transcriptions'
const MODEL = 'whisper-large-v3-turbo'
/** Segments are seconds long; anything larger is not one of ours. */
const MAX_BYTES = 8 * 1024 * 1024

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

  // Verify the caller against Supabase auth before spending any quota.
  const authHeader = req.headers.get('Authorization') ?? ''
  const supabase = createClient(
    Deno.env.get('SUPABASE_URL')!,
    Deno.env.get('SUPABASE_ANON_KEY')!,
    { global: { headers: { Authorization: authHeader } } },
  )
  const { data: userData, error: authError } = await supabase.auth.getUser()
  if (authError || !userData.user) return json({ error: 'Not signed in.' }, 401)

  let form: FormData
  try {
    form = await req.formData()
  } catch {
    return json({ error: 'Expected multipart/form-data.' }, 400)
  }

  const file = form.get('file')
  if (!(file instanceof File)) return json({ error: 'Missing audio file.' }, 400)
  if (file.size > MAX_BYTES) return json({ error: 'Segment too large.' }, 413)

  // Rebuild the form rather than forwarding it, so a caller cannot smuggle in
  // extra parameters or a different model.
  const out = new FormData()
  out.append('file', file, file.name || 'segment.webm')
  out.append('model', MODEL)
  out.append('response_format', 'json')
  out.append('temperature', '0')
  const lang = form.get('language')
  if (typeof lang === 'string' && /^[a-z]{2}$/.test(lang)) out.append('language', lang)
  const prompt = form.get('prompt')
  if (typeof prompt === 'string' && prompt) out.append('prompt', prompt.slice(0, 900))

  const res = await fetch(GROQ_URL, {
    method: 'POST',
    headers: { Authorization: `Bearer ${key}` },
    body: out,
  })

  const text = await res.text()
  return new Response(text, {
    status: res.status,
    headers: { ...cors, 'Content-Type': 'application/json' },
  })
})
