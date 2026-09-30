import { Vad } from './vad'

export interface RecordedSegment {
  index: number
  startMs: number
  endMs: number
  blob: Blob
  mime: string
}

export interface RecorderEvents {
  onSegment: (seg: RecordedSegment) => void
  onMeter: (level: number, speaking: boolean) => void
  onError: (err: Error) => void
}

export interface RecorderOptions {
  deviceId?: string
  minSegmentMs: number
  maxSegmentMs: number
  silenceHoldMs: number
}

/** Opus at 20kbps is transparent enough for speech and costs ~9MB per hour. */
const BITRATE = 20_000
const TICK_MS = 100

/**
 * Picks a container the browser can actually produce AND Whisper can read.
 * Chrome/Firefox give webm/opus; Safari gives mp4/aac.
 */
function pickMime(): string {
  const candidates = [
    'audio/webm;codecs=opus',
    'audio/webm',
    'audio/mp4;codecs=mp4a.40.2',
    'audio/mp4',
    'audio/ogg;codecs=opus',
  ]
  for (const m of candidates) {
    if (typeof MediaRecorder !== 'undefined' && MediaRecorder.isTypeSupported(m)) return m
  }
  return ''
}

/**
 * Records continuously but emits standalone, independently-decodable audio files
 * cut at natural pauses.
 *
 * Why restart the MediaRecorder per segment instead of using `timeslice`: with
 * `timeslice`, only the first chunk carries the container header, so chunks 2..n
 * are not valid files on their own and a transcription API cannot read them.
 * Stopping and restarting gives every segment its own header. The cost is a gap
 * of a few milliseconds at each cut — which is precisely why we cut during
 * silence rather than on a fixed timer.
 */
export class SegmentingRecorder {
  private stream: MediaStream | null = null
  private ctx: AudioContext | null = null
  private source: MediaStreamAudioSourceNode | null = null
  private vad: Vad | null = null
  private rec: MediaRecorder | null = null
  private timer: number | null = null

  private mime = ''
  private index = 0
  private startedAt = 0
  private segStartMs = 0
  private silenceMs = 0
  private sawSpeech = false
  private stopping = false
  private paused = false

  constructor(private opts: RecorderOptions, private events: RecorderEvents) {}

  get mimeType() {
    return this.mime
  }

  async start() {
    this.mime = pickMime()
    if (!this.mime) throw new Error('This browser cannot record audio (no supported MediaRecorder format).')

    this.stream = await navigator.mediaDevices.getUserMedia({
      audio: {
        deviceId: this.opts.deviceId ? { exact: this.opts.deviceId } : undefined,
        channelCount: 1,
        echoCancellation: true,
        noiseSuppression: true,
        autoGainControl: true,
      },
    })

    this.ctx = new AudioContext()
    // A suspended context on mobile would freeze the meter and the VAD with it.
    if (this.ctx.state === 'suspended') await this.ctx.resume()
    this.source = this.ctx.createMediaStreamSource(this.stream)
    this.vad = new Vad(this.ctx, this.source)

    this.startedAt = performance.now()
    this.segStartMs = 0
    this.index = 0
    this.stopping = false
    this.paused = false

    this.openSegment()
    this.timer = window.setInterval(() => this.tick(), TICK_MS)
  }

  pause() {
    this.paused = true
    // Flush whatever is buffered so the pause point is a clean boundary.
    if (this.rec && this.rec.state === 'recording') this.rec.stop()
  }

  resume() {
    if (!this.paused) return
    this.paused = false
    this.segStartMs = this.elapsed()
    this.openSegment()
  }

  async stop(): Promise<void> {
    this.stopping = true
    if (this.timer !== null) {
      clearInterval(this.timer)
      this.timer = null
    }
    await new Promise<void>((resolve) => {
      if (!this.rec || this.rec.state === 'inactive') return resolve()
      const r = this.rec
      r.addEventListener('stop', () => resolve(), { once: true })
      r.stop()
    })
    this.vad?.disconnect()
    this.source?.disconnect()
    await this.ctx?.close().catch(() => {})
    this.stream?.getTracks().forEach((t) => t.stop())
    this.stream = null
    this.ctx = null
    this.source = null
    this.vad = null
    this.rec = null
  }

  private elapsed() {
    return performance.now() - this.startedAt
  }

  private openSegment() {
    if (!this.stream) return
    const chunks: Blob[] = []
    const startMs = this.segStartMs
    const index = this.index++
    const rec = new MediaRecorder(this.stream, { mimeType: this.mime, audioBitsPerSecond: BITRATE })

    rec.ondataavailable = (e) => {
      if (e.data && e.data.size > 0) chunks.push(e.data)
    }
    rec.onerror = () => this.events.onError(new Error('Recording failed — the microphone may have been disconnected.'))
    rec.onstop = () => {
      const endMs = this.elapsed()
      const blob = new Blob(chunks, { type: this.mime })
      // A segment with no detected speech is room tone. Sending it to Whisper
      // wastes quota and reliably produces hallucinated filler like "Thank you."
      if (this.sawSpeech && blob.size > 2000) {
        this.events.onSegment({ index, startMs, endMs, blob, mime: this.mime })
      }
      this.sawSpeech = false
      this.silenceMs = 0
      if (!this.stopping && !this.paused) {
        this.segStartMs = endMs
        this.openSegment()
      }
    }

    this.rec = rec
    this.sawSpeech = false
    this.silenceMs = 0
    rec.start()
  }

  private tick() {
    if (!this.vad || this.paused) return
    const { level, speaking } = this.vad.read()
    this.events.onMeter(level, speaking)

    if (speaking) {
      this.sawSpeech = true
      this.silenceMs = 0
    } else {
      this.silenceMs += TICK_MS
    }

    const dur = this.elapsed() - this.segStartMs
    const hitPause = this.sawSpeech && dur >= this.opts.minSegmentMs && this.silenceMs >= this.opts.silenceHoldMs
    const hitCap = dur >= this.opts.maxSegmentMs
    // Long silence with nothing said: recycle the segment so we don't accumulate
    // a huge empty blob during a break.
    const deadAir = !this.sawSpeech && dur >= this.opts.maxSegmentMs

    if ((hitPause || hitCap || deadAir) && this.rec?.state === 'recording') {
      this.rec.stop()
    }
  }
}

export async function listMicrophones(): Promise<MediaDeviceInfo[]> {
  const devices = await navigator.mediaDevices.enumerateDevices()
  return devices.filter((d) => d.kind === 'audioinput')
}
