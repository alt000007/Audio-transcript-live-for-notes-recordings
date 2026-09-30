import type { Settings } from '../types'
import { transcribeWithGroq } from './groq'

export interface TranscribeRequest {
  blob: Blob
  mime: string
  language: string
  /**
   * Text immediately preceding this segment plus the user's glossary.
   * Whisper accepts this as a decoding prior, which is the cheapest and most
   * effective fix for mangled jargon and proper nouns — far better than trying
   * to repair them afterwards with an LLM.
   */
  prompt: string
  signal?: AbortSignal
}

export interface Transcriber {
  id: string
  label: string
  transcribe: (req: TranscribeRequest) => Promise<string>
}

export class TranscribeError extends Error {
  constructor(message: string, readonly retryable: boolean, readonly status?: number) {
    super(message)
    this.name = 'TranscribeError'
  }
}

export function getTranscriber(settings: Settings): Transcriber {
  switch (settings.engine) {
    case 'groq-byok':
      return {
        id: 'groq-byok',
        label: 'Whisper large-v3-turbo (your Groq key)',
        transcribe: (req) => transcribeWithGroq(req, { apiKey: settings.groqKey }),
      }
    case 'webspeech':
      // Web Speech does not take an audio file, so it cannot satisfy this
      // interface. useRecorder routes around it entirely; this exists so the
      // switch is exhaustive and a misconfiguration fails loudly.
      return {
        id: 'webspeech',
        label: 'Browser speech recognition',
        transcribe: async () => {
          throw new TranscribeError('Browser speech recognition does not transcribe recorded files.', false)
        },
      }
    case 'groq':
    default:
      return {
        id: 'groq',
        label: 'Whisper large-v3-turbo (shared key)',
        transcribe: (req) => transcribeWithGroq(req, {}),
      }
  }
}

/** Builds the decoding prior: glossary first, then recent context. */
export function buildPrompt(glossary: string, recentText: string): string {
  const parts: string[] = []
  const terms = glossary.trim()
  if (terms) parts.push(terms.replace(/\s*\n\s*/g, ', '))
  const tail = recentText.trim().slice(-400)
  if (tail) parts.push(tail)
  // Whisper truncates the prior at 224 tokens; keep it comfortably under.
  return parts.join(' ').slice(-900)
}
