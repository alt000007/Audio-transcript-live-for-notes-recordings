/**
 * Cheap energy-based voice activity detection.
 *
 * We deliberately avoid a neural VAD: this runs for the length of a lecture on a
 * phone, and an AnalyserNode reading a float array every 100ms costs almost
 * nothing. We only need "is someone talking right now" accurately enough to cut
 * segments at pauses — not phoneme-accurate boundaries.
 */
export interface VadReading {
  /** 0..1, roughly perceptual. Drives the level meter. */
  level: number
  speaking: boolean
}

/** Speech must exceed the floor by this much to start a talk-spurt. */
const ONSET_DB = 9
/** ...and fall below this much to end one. The gap is hysteresis, so the
 *  detector does not chatter across the threshold between syllables. */
const RELEASE_DB = 4.5

/** The floor drops toward a quiet room quickly... */
const DOWN_ALPHA = 0.3
/** ...and rises glacially. Time constant ~3 minutes at a 100ms tick.
 *
 *  This asymmetry is the whole design. A symmetric tracker chases sustained
 *  sound upward, so a lecturer who speaks without pausing — or a room with a
 *  steady fan — drags the floor up until speech no longer clears the threshold
 *  and the detector goes deaf mid-segment. Rising this slowly means a genuinely
 *  louder room is still learned, but never within one segment. */
const UP_ALPHA = 0.0005

/** Never treat anything below this as speech; digital silence has no floor. */
const ABSOLUTE_MIN = 0.0012
/** Cap the floor well below normal speech level so it can never blind us. */
const FLOOR_CEILING = 0.02

export class Vad {
  private analyser: AnalyserNode
  private buf: Float32Array<ArrayBuffer>
  private noiseFloor = 0.004
  private speaking = false

  constructor(ctx: AudioContext, source: MediaStreamAudioSourceNode) {
    this.analyser = ctx.createAnalyser()
    this.analyser.fftSize = 1024
    this.analyser.smoothingTimeConstant = 0.3
    source.connect(this.analyser)
    this.buf = new Float32Array(new ArrayBuffer(this.analyser.fftSize * 4))
  }

  read(): VadReading {
    this.analyser.getFloatTimeDomainData(this.buf)
    let sum = 0
    for (let i = 0; i < this.buf.length; i++) sum += this.buf[i] * this.buf[i]
    const rms = Math.sqrt(sum / this.buf.length)

    const alpha = rms < this.noiseFloor ? DOWN_ALPHA : UP_ALPHA
    this.noiseFloor = this.noiseFloor * (1 - alpha) + rms * alpha
    this.noiseFloor = Math.min(Math.max(this.noiseFloor, ABSOLUTE_MIN), FLOOR_CEILING)

    const gate = this.speaking ? RELEASE_DB : ONSET_DB
    const threshold = Math.max(this.noiseFloor * Math.pow(10, gate / 20), ABSOLUTE_MIN * 2)
    this.speaking = rms > threshold

    // Map to a meter that looks right: -60dB..0dB across the bar.
    const db = 20 * Math.log10(Math.max(rms, 1e-6))
    const level = Math.min(1, Math.max(0, (db + 60) / 60))

    return { level, speaking: this.speaking }
  }

  disconnect() {
    this.analyser.disconnect()
  }
}
