export type SessionStatus = 'recording' | 'stopped' | 'processing' | 'ready'

/** A recording session: one class, one script take. */
export interface Session {
  id: string
  title: string
  createdAt: number
  updatedAt: number
  durationMs: number
  status: SessionStatus
  /** BCP-47-ish hint passed to Whisper. '' = autodetect. */
  language: string
  /** Structured notes produced by the cleanup pass. Null until it runs. */
  notes: Notes | null
  /** Set once the row exists in Supabase. */
  remoteId: string | null
  /** Local rows not yet pushed to Supabase. */
  dirty: boolean
}

export type SegmentStatus = 'pending' | 'uploading' | 'done' | 'failed' | 'skipped'

/**
 * One silence-delimited slice of audio and its transcript.
 * The audio blob is dropped from IndexedDB once the text is `done` and synced,
 * unless the user has opted to keep audio.
 */
export interface Segment {
  id: string
  sessionId: string
  index: number
  startMs: number
  endMs: number
  blob: Blob | null
  bytes: number
  text: string
  status: SegmentStatus
  attempts: number
  error: string | null
}

/** Output of the structuring pass. Derived — the raw segments stay authoritative. */
export interface Notes {
  summary: string
  topics: Topic[]
  /** Terms the model corrected, so you can audit what it changed. */
  corrections: Correction[]
  generatedAt: number
  model: string
}

export interface Topic {
  heading: string
  /** Millisecond offset into the recording where this topic starts. */
  startMs: number
  bullets: string[]
}

export interface Correction {
  from: string
  to: string
}

export interface Settings {
  /** 'groq' uses the shared edge-function key; 'groq-byok' uses the user's own. */
  engine: 'groq' | 'groq-byok' | 'webspeech'
  groqKey: string
  language: string
  /** Domain words Whisper should bias toward — course names, jargon, people. */
  glossary: string
  /** Keep segment audio in IndexedDB after transcription. */
  keepAudio: boolean
  minSegmentMs: number
  maxSegmentMs: number
  silenceHoldMs: number
}

export const DEFAULT_SETTINGS: Settings = {
  engine: 'groq',
  groqKey: '',
  language: '',
  glossary: '',
  keepAudio: false,
  minSegmentMs: 4000,
  maxSegmentMs: 12000,
  silenceHoldMs: 550,
}
