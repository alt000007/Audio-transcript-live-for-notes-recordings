package app.scribe

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Energy-based voice activity detection, matching the web build's behaviour.
 *
 * The asymmetry matters: the noise floor falls quickly toward a quiet room and
 * rises over roughly three minutes. A symmetric tracker chases sustained sound
 * upward until speech no longer clears the threshold, which makes the detector
 * go deaf part-way through an evenly-paced lecture. Separate onset and release
 * thresholds stop it chattering between syllables.
 */
class Vad {
    private var floor = 0.004f
    var speaking = false
        private set

    /** Peak loudness seen since [resetPeak]; lets a caller tell "no speech"
     *  apart from "no sound at all". */
    var peak = 0f
        private set

    fun resetPeak() { peak = 0f }

    /** @return level in 0..1 for a meter. */
    fun push(pcm: ShortArray, count: Int): Float {
        var sum = 0.0
        for (i in 0 until count) {
            val v = pcm[i] / 32768.0
            sum += v * v
        }
        val rms = sqrt(sum / max(1, count)).toFloat()
        peak = max(peak, rms)

        val a = if (rms < floor) DOWN else UP
        floor = min(max(floor * (1 - a) + rms * a, MIN_RMS), FLOOR_CAP)

        val gate = if (speaking) RELEASE_DB else ONSET_DB
        speaking = rms > max(floor * 10f.pow(gate / 20f), MIN_RMS * 2)

        val db = 20f * log10(max(rms, 1e-6f))
        return min(1f, max(0f, (db + 60f) / 60f))
    }

    companion object {
        private const val ONSET_DB = 9f
        private const val RELEASE_DB = 4.5f
        private const val DOWN = 0.3f
        private const val UP = 0.0005f
        private const val MIN_RMS = 0.0012f
        private const val FLOOR_CAP = 0.02f
        /** Any sound at all, speech or not. */
        const val AUDIBLE = 0.006f
    }
}
