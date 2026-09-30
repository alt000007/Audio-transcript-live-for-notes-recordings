import { useEffect, useRef } from 'react'
import { useLiveQuery } from 'dexie-react-hooks'
import { db } from '../lib/db'
import { fmtTime } from '../lib/export'
import { queue } from '../lib/queue'

interface Props {
  sessionId: string
  /** Stick to the bottom as new text lands. Off when reviewing a finished session. */
  follow?: boolean
}

export function LiveTranscript({ sessionId, follow = false }: Props) {
  const segs = useLiveQuery(
    () => db.segments.where('sessionId').equals(sessionId).sortBy('index'),
    [sessionId],
    [],
  )
  const endRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (follow) endRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' })
  }, [segs?.length, follow])

  const visible = (segs ?? []).filter((s) => s.status !== 'skipped')

  if (visible.length === 0) {
    return (
      <p className="faint small" style={{ textAlign: 'center', padding: '28px 0' }}>
        {follow ? 'Listening — text appears after the first pause.' : 'No transcript for this recording.'}
      </p>
    )
  }

  return (
    <div className="transcript">
      {visible.map((s) => (
        <div key={s.id} className={`tline ${s.status}`}>
          <span className="ts">{fmtTime(s.startMs)}</span>
          <span className="txt grow">
            {s.status === 'done' && s.text}
            {(s.status === 'pending' || s.status === 'uploading') && <span className="dots">transcribing</span>}
            {s.status === 'failed' && (
              <>
                {s.error ?? 'Could not transcribe this part.'}{' '}
                {s.blob && (
                  <button className="btn ghost sm" onClick={() => void queue.retryFailed(sessionId)}>
                    Retry
                  </button>
                )}
              </>
            )}
          </span>
        </div>
      ))}
      <div ref={endRef} />
    </div>
  )
}
