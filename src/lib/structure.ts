import { functionsUrl, supabase } from './supabase'
import type { Notes, Settings, Topic } from './types'

const GROQ_CHAT = 'https://api.groq.com/openai/v1/chat/completions'
export const STRUCTURE_MODEL = 'llama-3.3-70b-versatile'

/**
 * The cleanup pass is allowed to repair *transcription* and impose *structure*.
 * It is not allowed to add information. For class notes this is the whole ball
 * game: notes you cannot trust are worse than a messy transcript, because you
 * will study from them without checking.
 */
const SYSTEM = `You clean up automatic speech-recognition output from a lecture or spoken recording and organise it into notes.

HARD RULES — these override everything else:
- Never add facts, examples, definitions, dates, names or conclusions that are not present in the transcript. If the speaker did not say it, it does not appear.
- Never fill a gap. If the transcript is garbled beyond repair, keep the readable part and write [unclear] for the rest.
- Do not answer questions the speaker asked, and do not resolve things they left open.
- Preserve the speaker's meaning exactly. You may fix grammar of transcription, not of the speaker's argument.

WHAT YOU MAY DO:
- Fix words the recogniser clearly misheard, using surrounding context (for example a technical term mangled into a common word). Record every such fix in "corrections".
- Add punctuation, sentence breaks and capitalisation.
- Remove filler ("um", "uh", false starts, repeated words) and transcription stutter.
- Group the content into topics in the order they were discussed, and give each a short heading in the transcript's own language.
- Write bullets that compress what was actually said.

Each topic carries "startMs", copied from the [t=NNNN] marker nearest the start of that topic's content.

Reply with JSON only, matching:
{"summary": "2-3 sentences on what the recording covered",
 "topics": [{"heading": "...", "startMs": 0, "bullets": ["...", "..."]}],
 "corrections": [{"from": "what the recogniser produced", "to": "what was meant"}]}`

interface RawNotes {
  summary?: string
  topics?: { heading?: string; startMs?: number; bullets?: string[] }[]
  corrections?: { from?: string; to?: string }[]
}

/** Interleaves timestamp markers so the model can anchor headings to the audio. */
export function markUp(chunks: { startMs: number; text: string }[]): string {
  return chunks.map((c) => `[t=${Math.round(c.startMs)}] ${c.text}`).join('\n')
}

function coerce(raw: RawNotes, durationMs: number): Notes {
  const topics: Topic[] = (raw.topics ?? [])
    .map((t) => ({
      heading: (t.heading ?? '').trim() || 'Untitled',
      startMs: Number.isFinite(t.startMs) ? Math.max(0, Math.min(durationMs, Number(t.startMs))) : 0,
      bullets: (t.bullets ?? []).map((b) => String(b).trim()).filter(Boolean),
    }))
    .filter((t) => t.bullets.length > 0)
    .sort((a, b) => a.startMs - b.startMs)

  const corrections = (raw.corrections ?? [])
    .map((c) => ({ from: String(c.from ?? '').trim(), to: String(c.to ?? '').trim() }))
    .filter((c) => c.from && c.to && c.from !== c.to)

  return {
    summary: (raw.summary ?? '').trim(),
    topics,
    corrections,
    generatedAt: Date.now(),
    model: STRUCTURE_MODEL,
  }
}

export async function structureTranscript(
  chunks: { startMs: number; text: string }[],
  durationMs: number,
  settings: Settings,
  signal?: AbortSignal,
): Promise<Notes> {
  if (chunks.length === 0) throw new Error('Nothing to structure yet — this recording has no transcript.')

  const body = {
    model: STRUCTURE_MODEL,
    temperature: 0.2,
    response_format: { type: 'json_object' as const },
    messages: [
      { role: 'system' as const, content: SYSTEM },
      {
        role: 'user' as const,
        content: `Transcript (timestamps in milliseconds):\n\n${markUp(chunks)}`,
      },
    ],
  }

  let url: string
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }

  if (settings.engine === 'groq-byok' && settings.groqKey) {
    url = GROQ_CHAT
    headers.Authorization = `Bearer ${settings.groqKey}`
  } else {
    const fn = functionsUrl('structure')
    if (!fn) throw new Error('No backend configured. Add a Groq API key in Settings to generate notes.')
    url = fn
    const { data } = (await supabase?.auth.getSession()) ?? { data: { session: null } }
    if (!data.session) throw new Error('Sign in to generate notes, or add your own Groq key in Settings.')
    headers.Authorization = `Bearer ${data.session.access_token}`
  }

  const res = await fetch(url, { method: 'POST', headers, body: JSON.stringify(body), signal })
  if (!res.ok) {
    const text = await res.text().catch(() => '')
    throw new Error(`Could not generate notes (${res.status}). ${text.slice(0, 200)}`)
  }

  const json = (await res.json()) as { choices?: { message?: { content?: string } }[] }
  const content = json.choices?.[0]?.message?.content
  if (!content) throw new Error('The notes model returned an empty response.')

  let parsed: RawNotes
  try {
    parsed = JSON.parse(content) as RawNotes
  } catch {
    throw new Error('The notes model returned malformed JSON.')
  }
  return coerce(parsed, durationMs)
}
