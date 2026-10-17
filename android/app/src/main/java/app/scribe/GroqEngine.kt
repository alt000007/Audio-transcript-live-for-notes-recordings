package app.scribe

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Whisper large-v3-turbo on Groq's free plan.
 *
 * Same model and settings the page has always used, so accuracy is unchanged.
 * Running it here instead of in the page is what lets the queue carry on with
 * the app closed. The key is never stored in this class: it is read from the
 * phone's keystore-backed storage each time it is needed.
 */
class GroqEngine(
    private val key: () -> String?,
    private val base: String = "https://api.groq.com/openai/v1/",
    /** The free plan allows 20 requests a minute; spacing starts keeps under it rather than bouncing off it. */
    private val minIntervalMs: Long = 3_100,
) : Engine {
    override val id = "groq"
    override val parallel = 6

    private val pace = Object()
    private var lastStart = 0L

    private fun waitForTurn(cancelled: () -> Boolean) {
        synchronized(pace) {
            while (true) {
                val wait = lastStart + minIntervalMs - System.currentTimeMillis()
                if (wait <= 0 || cancelled()) break
                (pace as Object).wait(wait)
            }
            lastStart = System.currentTimeMillis()
        }
    }

    override fun unavailable(): String? =
        if (key().isNullOrBlank()) "Add a free Groq key in Settings, or download the on-device model" else null

    override fun transcribe(audio: File, prompt: String, lang: String, cancelled: () -> Boolean): List<Seg> {
        val k = key()
        if (k.isNullOrBlank()) throw KeyRejected("Add a free Groq key in Settings")
        waitForTurn(cancelled)
        val boundary = "scribe-" + UUID.randomUUID().toString()
        val conn: HttpURLConnection
        try {
            conn = URL(base + "audio/transcriptions").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 20_000
            conn.readTimeout = 180_000
            conn.setRequestProperty("Authorization", "Bearer $k")
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            // Stream the body rather than buffering it all in memory.
            conn.setChunkedStreamingMode(64 * 1024)

            conn.outputStream.buffered().use { out ->
                fun field(name: String, value: String) {
                    out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
                }
                field("model", MODEL)
                field("response_format", "verbose_json")
                field("temperature", "0")
                if (lang.isNotBlank()) field("language", lang)
                if (prompt.isNotBlank()) field("prompt", prompt)
                val name = "audio." + audio.extension.ifEmpty { "m4a" }
                out.write(
                    ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$name\"\r\n" +
                        "Content-Type: ${mimeFor(audio.extension)}\r\n\r\n").toByteArray(),
                )
                audio.inputStream().use { it.copyTo(out, 64 * 1024) }
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }
        } catch (e: IOException) {
            throw Transient("No connection (${e.javaClass.simpleName})")
        }

        try {
            val code = try { conn.responseCode } catch (e: IOException) { throw Transient("No connection (${e.javaClass.simpleName})") }
            val body = try {
                (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            } catch (e: IOException) {
                throw Transient("Connection dropped while reading the reply")
            }
            when {
                code in 200..299 -> return parse(body)
                code == 429 -> {
                    val header = conn.getHeaderField("retry-after")?.toDoubleOrNull()?.let { (it * 1000).toLong() }
                    val stated = retryDelayFrom(body)
                    val wait = maxOf(header ?: 0L, stated ?: 0L).takeIf { it > 0 } ?: 20_000L
                    throw RateLimited(wait + 750, "Rate limit reached")
                }
                code == 401 -> throw KeyRejected("Groq rejected the key — check it in Settings")
                code >= 500 -> throw Transient("Groq is having trouble ($code)")
                else -> throw Fatal("Groq refused this part ($code): ${readable(body)}")
            }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val MODEL = "whisper-large-v3-turbo"

        private fun mimeFor(ext: String) = when (ext.lowercase()) {
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg", "opus" -> "audio/ogg"
            "webm" -> "audio/webm"
            "flac" -> "audio/flac"
            else -> "audio/mp4"
        }

        /** "Please try again in 1m23.456s" — the same figure the page reads. */
        fun retryDelayFrom(body: String): Long? {
            val m = Regex("try again in\\s+(?:(\\d+)h)?(?:(\\d+)m)?([\\d.]+)s", RegexOption.IGNORE_CASE).find(body) ?: return null
            val h = m.groupValues[1].toLongOrNull() ?: 0
            val min = m.groupValues[2].toLongOrNull() ?: 0
            val sec = m.groupValues[3].toDoubleOrNull() ?: 0.0
            return ((h * 3600 + min * 60) * 1000 + sec * 1000).toLong()
        }

        private fun readable(body: String): String {
            val msg = runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrNull()
            return (msg ?: body.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ")).trim().take(160)
        }

        /**
         * Whisper's own segments, minus the ones it marks as probably not
         * speech. Those are where it invents a closing "thank you" or a
         * subtitle credit over silence; Whisper itself skips a window on the
         * same test.
         */
        fun parse(body: String): List<Seg> {
            val json = JSONObject(body)
            val segs = json.optJSONArray("segments")
            if (segs == null || segs.length() == 0) {
                val t = json.optString("text").trim()
                return if (t.isEmpty()) emptyList() else listOf(Seg(0, 0, t))
            }
            val out = ArrayList<Seg>()
            for (i in 0 until segs.length()) {
                val s = segs.getJSONObject(i)
                val text = s.optString("text").trim()
                if (text.isEmpty()) continue
                val noSpeech = s.optDouble("no_speech_prob", 0.0)
                val logprob = s.optDouble("avg_logprob", 0.0)
                if (noSpeech > 0.6 && logprob < -1.0) continue
                out.add(Seg((s.optDouble("start") * 1000).toLong(), (s.optDouble("end") * 1000).toLong(), text))
            }
            return out
        }
    }
}
