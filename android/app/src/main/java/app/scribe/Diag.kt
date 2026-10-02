package app.scribe

import org.json.JSONArray
import org.json.JSONObject

/**
 * A short, in-memory log of what the native side actually did.
 *
 * The web layer can be inspected from a laptop; the service and the intent
 * plumbing cannot. Without this, a report of "recording does nothing" is
 * indistinguishable from "the microphone is muted", "the service never
 * started" and "segments were produced but never reached the page" — and
 * guessing between them has cost several rounds.
 */
object Diag {

    private const val LIMIT = 120
    private val events = ArrayDeque<JSONObject>()
    private val started = System.currentTimeMillis()

    /** Counters that matter more than any single line of the log. */
    @Volatile var segmentsEmitted = 0
    @Volatile var segmentsDelivered = 0
    @Volatile var levelSamples = 0
    @Volatile var lastLevel = 0f
    @Volatile var audioSource = "none"

    @Synchronized
    fun log(what: String, detail: String = "") {
        events.addLast(
            JSONObject()
                .put("t", System.currentTimeMillis() - started)
                .put("what", what)
                .put("detail", detail),
        )
        while (events.size > LIMIT) events.removeFirst()
    }

    @Synchronized
    fun snapshot(extra: Map<String, Any?> = emptyMap()): String {
        val out = JSONObject()
            .put("upSeconds", (System.currentTimeMillis() - started) / 1000)
            .put("serviceRunning", RecordingService.isRunning)
            .put("audioSource", audioSource)
            .put("segmentsEmitted", segmentsEmitted)
            .put("segmentsDelivered", segmentsDelivered)
            .put("levelSamples", levelSamples)
            .put("lastLevel", String.format("%.4f", lastLevel))
        extra.forEach { (k, v) -> out.put(k, v) }
        out.put("events", JSONArray(events.toList()))
        return out.toString()
    }
}
