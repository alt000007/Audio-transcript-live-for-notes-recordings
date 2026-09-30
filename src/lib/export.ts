import type { Notes, Session } from './types'

export function fmtTime(ms: number): string {
  const total = Math.floor(ms / 1000)
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  const pad = (n: number) => String(n).padStart(2, '0')
  return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${m}:${pad(s)}`
}

export function notesToMarkdown(session: Session, notes: Notes | null, transcript: string): string {
  const out: string[] = []
  out.push(`# ${session.title}`, '')
  out.push(
    `*${new Date(session.createdAt).toLocaleString()} · ${fmtTime(session.durationMs)}*`,
    '',
  )

  if (notes) {
    if (notes.summary) out.push('## Summary', '', notes.summary, '')
    for (const topic of notes.topics) {
      out.push(`## ${topic.heading}`, '', `*${fmtTime(topic.startMs)}*`, '')
      for (const b of topic.bullets) out.push(`- ${b}`)
      out.push('')
    }
    if (notes.corrections.length) {
      out.push('## Terms corrected', '')
      for (const c of notes.corrections) out.push(`- "${c.from}" → **${c.to}**`)
      out.push('')
    }
  }

  if (transcript) {
    out.push('---', '', '## Full transcript', '', transcript, '')
  }
  return out.join('\n')
}

export function download(filename: string, contents: string, mime = 'text/markdown') {
  const blob = new Blob([contents], { type: `${mime};charset=utf-8` })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  // Revoke on the next tick so Safari has time to start the download.
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

export function safeFilename(title: string): string {
  return title.replace(/[^\p{L}\p{N}\s-]/gu, '').trim().replace(/\s+/g, '-').slice(0, 60) || 'recording'
}
