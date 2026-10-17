package app.scribe

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Converts any sample rate to another, with a proper low-pass filter.
 *
 * Plain linear interpolation from 44.1 or 48 kHz down to 16 kHz folds
 * everything above 8 kHz back into the speech band as noise. A short
 * windowed-sinc filter, precomputed for a fixed set of fractional positions,
 * avoids that for a few multiplications per output sample.
 */
class Resampler(private val inRate: Int, private val outRate: Int) {
    private val step = inRate.toDouble() / outRate
    private val passthrough = inRate == outRate
    private val taps = HALF * 2
    private val table: Array<FloatArray>

    private var buf = FloatArray(8192)
    private var len = 0
    /** Position of the next output sample, in input samples from buf[0]. */
    private var pos = HALF.toDouble()

    init {
        // Cut-off just under the lower of the two Nyquist frequencies, in
        // cycles per input sample.
        val fc = 0.45 * min(inRate, outRate) / inRate
        table = Array(PHASES) { p ->
            val frac = p.toDouble() / PHASES
            val k = FloatArray(taps)
            var sum = 0.0
            for (t in 0 until taps) {
                val x = (t - HALF + 1) - frac
                val sinc = if (x == 0.0) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
                val w = 0.5 + 0.5 * cos(PI * x / HALF)   // Hann window
                val v = sinc * w
                k[t] = v.toFloat()
                sum += v
            }
            for (t in 0 until taps) k[t] = (k[t] / sum).toFloat()
            k
        }
        // Prime with silence so the first samples have a full window behind them.
        len = HALF
    }

    /** Feeds [n] samples; resampled output is passed to [out] in blocks. */
    fun push(input: FloatArray, n: Int, out: (FloatArray, Int) -> Unit) {
        if (passthrough) { out(input, n); return }
        if (len + n > buf.size) buf = buf.copyOf(max(buf.size * 2, len + n))
        System.arraycopy(input, 0, buf, len, n)
        len += n

        val maxOut = ((len - HALF - pos) / step).toInt() + 2
        val o = FloatArray(max(0, maxOut))
        var count = 0
        while (pos + HALF < len && count < o.size) {
            val base = floor(pos).toInt()
            val frac = pos - base
            val k = table[min(PHASES - 1, (frac * PHASES).toInt())]
            var acc = 0f
            val start = base - HALF + 1
            for (t in 0 until taps) acc += buf[start + t] * k[t]
            o[count++] = acc
            pos += step
        }
        if (count > 0) out(o, count)

        // Drop what no future output can reach.
        val drop = floor(pos).toInt() - HALF
        if (drop > 0) {
            System.arraycopy(buf, drop, buf, 0, len - drop)
            len -= drop
            pos -= drop
        }
    }

    companion object {
        private const val HALF = 16
        private const val PHASES = 128
    }
}

/**
 * Shortens long silences and cuts 16 kHz mono audio into pieces.
 *
 * Shortening silence does three things at once: less audio to upload, less of
 * the free allowance spent (it is metered in seconds of audio), and less for
 * Whisper to hallucinate on — long pauses are exactly where it invents "thank
 * you for watching" and the strings of dots that used to fill transcripts.
 *
 * It is cautious by design. A pause is kept whole up to [KEEP_MS]; only the
 * middle of a longer one is removed, and "silent" means close to the room's
 * own noise floor, so quiet speech is never mistaken for it. Every removal is
 * recorded, so transcript times still point at the right place in the
 * original recording.
 *
 * Pieces are cut at a pause once they pass [targetMs], or forced at
 * [maxMs] if the speaker never stops.
 */
class Chunker(
    private val startOrigMs: Long,
    private val trim: Boolean,
    private val targetMs: Long = 300_000,
    private val maxMs: Long = 360_000,
    private val emit: (pcm: ShortArray, len: Int, startOrigMs: Long, endOrigMs: Long, map: LongArray) -> Unit,
) {
    private val frame = RATE / 50            // 20 ms
    private val headFrames = KEEP_MS / 2 / 20
    private val tailFrames = KEEP_MS / 2 / 20

    private var pcm = ShortArray(RATE * 60)
    private var pcmLen = 0
    private val map = ArrayList<Long>()
    private var chunkStartOrig = startOrigMs

    private val cur = ShortArray(frame)
    private var curLen = 0
    /** Original time of the frame being filled. */
    private var origMs = startOrigMs

    private var floor = 0.004f
    private var silentRun = 0
    private val tail = ArrayDeque<ShortArray>()
    private var dropped = false

    var keptMs = 0L
        private set
    var seenMs = 0L
        private set

    fun push(samples: FloatArray, n: Int) {
        for (i in 0 until n) {
            val v = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            cur[curLen++] = v
            if (curLen == frame) {
                onFrame(cur.copyOf())
                curLen = 0
            }
        }
    }

    /** Flushes the remainder as the last piece. */
    fun finish() {
        if (curLen > 0) onFrame(cur.copyOf(curLen))
        curLen = 0
        // A trailing pause is not worth uploading.
        tail.clear()
        if (pcmLen > RATE / 2) cut() else pcmLen = 0
    }

    private fun onFrame(f: ShortArray) {
        val ms = f.size * 1000L / RATE
        val silent = trim && isSilent(f)
        seenMs += ms

        if (!silent) {
            // Speech again after a pause: restore the end of the pause, so
            // words do not start abruptly.
            if (tail.isNotEmpty()) {
                var t = origMs - tail.sumOf { it.size } * 1000L / RATE
                if (dropped && pcmLen > 0) mark(t)
                for (fr in tail) { append(fr, t); t += fr.size * 1000L / RATE }
                tail.clear()
            }
            dropped = false
            silentRun = 0
            append(f, origMs)
        } else {
            silentRun++
            if (silentRun <= headFrames) {
                append(f, origMs)
            } else {
                tail.addLast(f)
                if (tail.size > tailFrames) { tail.removeFirst(); dropped = true }
                // In the middle of a long pause is the best place to cut.
                if (dropped && pcmLen >= samplesFor(targetMs)) {
                    tail.clear()
                    cut()
                    silentRun = 0
                }
            }
        }
        origMs += ms
        if (pcmLen >= samplesFor(maxMs)) cut()
    }

    private fun isSilent(f: ShortArray): Boolean {
        var sum = 0.0
        for (s in f) { val v = s / 32768.0; sum += v * v }
        val rms = sqrt(sum / f.size).toFloat()
        val a = if (rms < floor) DOWN else UP
        floor = min(max(floor * (1 - a) + rms * a, MIN_RMS), FLOOR_CAP)
        return rms < floor * SILENCE_RATIO || rms < MIN_RMS
    }

    /** Starts a new stretch in the map: piece time now corresponds to [orig]. */
    private fun mark(orig: Long) {
        val pieceMs = pcmLen * 1000L / RATE
        if (map.size >= 2 && map[map.size - 2] == pieceMs) {
            map[map.size - 1] = orig
        } else {
            map.add(pieceMs); map.add(orig)
        }
    }

    /** [at] is where this frame sits in the original recording. */
    private fun append(f: ShortArray, at: Long) {
        if (pcmLen == 0) {
            map.clear()
            chunkStartOrig = at
            map.add(0L); map.add(at)
        }
        if (pcmLen + f.size > pcm.size) pcm = pcm.copyOf(max(pcm.size * 2, pcmLen + f.size))
        System.arraycopy(f, 0, pcm, pcmLen, f.size)
        pcmLen += f.size
        keptMs += f.size * 1000L / RATE
    }

    private fun cut() {
        if (pcmLen == 0) return
        val end = origMs
        emit(pcm, pcmLen, chunkStartOrig, end, map.toLongArray())
        pcmLen = 0
        map.clear()
    }

    private fun samplesFor(ms: Long) = (ms * RATE / 1000).toInt()

    companion object {
        const val RATE = 16_000
        /** Pauses up to this long are kept whole. */
        const val KEEP_MS = 800
        /** Under 3 dB above the room's noise floor counts as silence. */
        private const val SILENCE_RATIO = 1.41f
        private const val DOWN = 0.3f
        private const val UP = 0.0005f
        private const val MIN_RMS = 0.0008f
        private const val FLOOR_CAP = 0.02f
    }
}
