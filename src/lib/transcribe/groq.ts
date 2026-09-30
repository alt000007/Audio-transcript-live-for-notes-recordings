import { functionsUrl, supabase } from '../supabase'
import { TranscribeError, type TranscribeRequest } from './index'

const GROQ_DIRECT = 'https://api.groq.com/openai/v1/audio/transcriptions'
export const GROQ_MODEL = 'whisper-large-v3-turbo'

function extFor(mime: string): string {
  if (mime.includes('webm')) return 'webm'
  if (mime.includes('ogg')) return 'ogg'
  if (mime.includes('mp4')) return 'm4a'
  if (mime.includes('wav')) return 'wav'
  return 'webm'
}

/** Whisper emits these on silence or music. They are not transcription. */
const HALLUCINATIONS = [
  'thank you.',
  'thanks for watching!',
  'thank you for watching.',
  'you',
  '.',
  'bye.',
  'subtitles by the amara.org community',
  '[music]',
  '[silence]',
  '[blank_audio]',
]

function clean(text: string): string {
  const t = text.trim()
  if (!t) return ''
  if (HALLUCINATIONS.includes(t.toLowerCase())) return ''
  return t
}

export async function transcribeWithGroq(
  req: TranscribeRequest,
  opts: { apiKey?: string },
): Promise<string> {
  const form = new FormData()
  form.append('file', req.blob, `segment.${extFor(req.mime)}`)
  form.append('model', GROQ_MODEL)
  form.append('response_format', 'json')
  // Low but non-zero: greedy decoding on short clips loops on itself more often.
  form.append('temperature', '0')
  if (req.language) form.append('language', req.language)
  if (req.prompt) form.append('prompt', req.prompt)

  let url: string
  const headers: Record<string, string> = {}

  if (opts.apiKey) {
    url = GROQ_DIRECT
    headers.Authorization = `Bearer ${opts.apiKey}`
  } else {
    const fn = functionsUrl('transcribe')
    if (!fn) {
      throw new TranscribeError(
        'No transcription backend configured. Add your own Groq API key in Settings, or connect a Supabase project.',
        false,
      )
    }
    url = fn
    const { data } = (await supabase?.auth.getSession()) ?? { data: { session: null } }
    const token = data.session?.access_token
    if (!token) {
      throw new TranscribeError('Sign in to use the shared transcription key, or add your own in Settings.', false)
    }
    headers.Authorization = `Bearer ${token}`
  }

  const res = await fetch(url, { method: 'POST', headers, body: form, signal: req.signal })

  if (!res.ok) {
    const body = await res.text().catch(() => '')
    // 429 is the free tier's rate limit, 5xx is transient: both are worth retrying.
    const retryable = res.status === 429 || res.status >= 500
    throw new TranscribeError(
      res.status === 429
        ? 'Groq rate limit reached — retrying shortly.'
        : `Transcription failed (${res.status}). ${body.slice(0, 200)}`,
      retryable,
      res.status,
    )
  }

  const json = (await res.json()) as { text?: string }
  return clean(json.text ?? '')
}
