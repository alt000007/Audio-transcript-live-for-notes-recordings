package app.scribe

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.Calendar

/**
 * Names recordings by what they are about: "Storia 03 - La rivoluzione
 * francese". The subject and theme come from the transcript; the number is
 * that recording's place among the subject's recordings, oldest first.
 *
 * Nothing here touches Android, so it is tested on a desktop JVM.
 */
object Naming {
    const val DEFAULT_FORMAT = "{subject} {n} - {theme}"

    /** Lower case, no accents, single spaces: "Storia " and "storia" are one subject. */
    fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .lowercase().replace(Regex("\\s+"), " ").trim()

    /** The subject as already spelled, if the model proposed one Scribe has seen. */
    fun matchSubject(proposed: String, known: List<String>): String {
        val p = fold(proposed)
        return known.firstOrNull { fold(it) == p } ?: proposed.trim()
    }

    /**
     * Fills in the pattern. Numbers are zero-padded to the width the subject
     * needs, so a file list sorted by name is also sorted in order:
     * "Storia 09" before "Storia 10".
     */
    fun format(pattern: String, subject: String, n: Int, width: Int, theme: String): String {
        val num = n.toString().padStart(width.coerceIn(1, 4), '0')
        val p = pattern.ifBlank { DEFAULT_FORMAT }
        val out = p.replace("{subject}", subject).replace("{n}", num).replace("{theme}", theme)
        return fileSafe(out)
    }

    /** Characters a phone's file system or a share target would choke on. */
    fun fileSafe(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim().trim('.', ' ', '-')
            .take(110).trim()

    /**
     * The recording time written into the file name by most recorders —
     * "Registrazione 2026-10-03 10-15-22", "20261003_101522", "Rec 2026.10.03
     * 10.15" — which survives copying and moving where file dates do not.
     */
    fun dateFromName(name: String): Long? {
        val m = Regex("(20\\d\\d)[-_. ]?([01]\\d)[-_. ]?([0-3]\\d)(?:[-_. T]+([0-2]\\d)[-_.:h ]?([0-5]\\d)(?:[-_.:m ]?([0-5]\\d))?)?")
            .find(name) ?: return null
        val (y, mo, d) = Triple(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        if (mo !in 1..12 || d !in 1..31) return null
        val c = Calendar.getInstance()
        c.clear()
        c.set(y, mo - 1, d, m.groupValues[4].toIntOrNull() ?: 0, m.groupValues[5].toIntOrNull() ?: 0, m.groupValues[6].toIntOrNull() ?: 0)
        return c.timeInMillis
    }

    /**
     * A sample of the transcript small enough to name it cheaply: the
     * opening, where a lecturer usually says what today is about, plus a
     * stretch from the middle and the end.
     */
    fun excerpt(text: String, max: Int = 6000): String {
        val t = text.replace(Regex("\\s+"), " ").trim()
        if (t.length <= max) return t
        val head = max / 2
        val part = max / 4
        val mid = t.length / 2
        return t.take(head) + " […] " + t.substring(mid - part / 2, mid + part / 2) + " […] " + t.takeLast(part)
    }

    /**
     * Numbers every job of each subject 1, 2, 3 … in recording order and
     * works out its title. @return the jobs whose title changed.
     */
    fun renumber(jobs: List<Job>, pattern: String): List<Job> {
        val changed = ArrayList<Job>()
        jobs.filter { it.naming == "done" && it.subject.isNotEmpty() }
            .groupBy { fold(it.subject) }
            .forEach { (_, group) ->
                val ordered = group.sortedWith(compareBy<Job>({ it.recordedAt.takeIf { r -> r > 0 } ?: it.createdAt }, { it.createdAt }, { it.id }))
                val width = maxOf(2, ordered.size.toString().length)
                // The spelling most of them use, capitalised if there is a tie.
                val subject = group.groupingBy { it.subject.trim() }.eachCount().entries
                    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { if (it.key.first().isUpperCase()) 0 else 1 })
                    .first().key
                ordered.forEachIndexed { i, j ->
                    val title = format(pattern, subject, i + 1, width, j.theme)
                    if (j.number != i + 1 || j.title != title || j.subject != subject) {
                        j.number = i + 1
                        j.subject = subject
                        j.title = title
                        changed.add(j)
                    }
                }
            }
        return changed
    }
}

/**
 * Asks a Groq chat model what a recording is: its subject and its theme.
 *
 * The user's own subject list wins when given; otherwise subjects Scribe has
 * already used are offered first, so one course does not end up spelled
 * three ways. Errors are reported in the same terms as transcription, so the
 * queue waits out rate limits and dropped connections the same way.
 */
class Classifier(
    private val key: () -> String?,
    private val base: String = "https://api.groq.com/openai/v1/",
    private val models: List<String> = MODELS,
) {
    @Volatile private var modelIndex = 0

    fun unavailable(): String? = if (key().isNullOrBlank()) "Add a free Groq key in Settings to name recordings" else null

    fun classify(transcript: String, userSubjects: List<String>, knownSubjects: List<String>, lang: String): Pair<String, String> {
        val k = key()
        if (k.isNullOrBlank()) throw KeyRejected("Add a free Groq key in Settings to name recordings")
        val text = Naming.excerpt(transcript)
        if (text.length < 20) return fallbackSubject(userSubjects, lang) to if (lang == "it") "Registrazione breve" else "Short recording"

        val rules = StringBuilder()
        if (userSubjects.isNotEmpty()) {
            rules.append("The subject MUST be exactly one of: ${userSubjects.joinToString(", ")}. ")
            rules.append("If none fits, use \"${fallbackSubject(userSubjects, lang)}\".\n")
        } else if (knownSubjects.isNotEmpty()) {
            rules.append("Subjects already in use: ${knownSubjects.joinToString(", ")}. Reuse one, spelled exactly the same, whenever it fits; only invent a new one for a clearly different subject.\n")
        }
        val system = """You name recordings of lessons and spoken notes.
Reply with JSON only: {"subject": "...", "theme": "..."}
- subject: the school subject or course this belongs to (e.g. Storia, Filosofia, Matematica, Diritto privato), 1-3 words, capitalised, in the language of the transcript.
- theme: the specific topic of THIS recording, 2-7 words, sentence case, in the language of the transcript, without repeating the subject, without quotes, dates or trailing punctuation.
- Base both only on what is actually said. If it is unclear, use a general theme such as "Ripasso" or "Varie" rather than guessing.
$rules""".trim()

        while (true) {
            val model = models[modelIndex.coerceAtMost(models.size - 1)]
            val body = JSONObject()
                .put("model", model)
                .put("temperature", 0.1)
                .put("response_format", JSONObject().put("type", "json_object"))
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", "Transcript excerpt:\n\n$text")))
            if (model.startsWith("openai/gpt-oss")) body.put("reasoning_effort", "low")

            val (code, reply, retryAfter) = post(k, body.toString())
            when {
                code in 200..299 -> return parse(reply, userSubjects, knownSubjects, lang)
                code == 404 || (code == 400 && reply.contains("model", ignoreCase = true) && reply.contains("decommission", ignoreCase = true)) -> {
                    // Retired model: step down the list.
                    if (modelIndex >= models.size - 1) throw Fatal("None of the naming models are available on this Groq account.")
                    modelIndex++
                }
                code == 429 -> {
                    val stated = GroqEngine.retryDelayFrom(reply)
                    val wait = maxOf(retryAfter ?: 0L, stated ?: 0L).takeIf { it > 0 } ?: 20_000L
                    throw RateLimited(wait + 750, "Rate limit reached")
                }
                code == 401 -> throw KeyRejected("Groq rejected the key — check it in Settings")
                code >= 500 -> throw Transient("Groq is having trouble ($code)")
                else -> throw Fatal("Groq could not name this recording ($code)")
            }
        }
    }

    private fun post(key: String, json: String): Triple<Int, String, Long?> {
        val conn: HttpURLConnection
        try {
            conn = URL(base + "chat/completions").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 20_000
            conn.readTimeout = 90_000
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        } catch (e: IOException) {
            throw Transient("No connection (${e.javaClass.simpleName})")
        }
        try {
            val code = try { conn.responseCode } catch (e: IOException) { throw Transient("No connection") }
            val body = try {
                (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            } catch (e: IOException) {
                throw Transient("Connection dropped while reading the reply")
            }
            val retry = conn.getHeaderField("retry-after")?.toDoubleOrNull()?.let { (it * 1000).toLong() }
            return Triple(code, body, retry)
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(reply: String, userSubjects: List<String>, knownSubjects: List<String>, lang: String): Pair<String, String> {
        val content = runCatching {
            JSONObject(reply).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        }.getOrNull() ?: throw Fatal("The naming model returned nothing.")
        val o = runCatching { JSONObject(content.substring(content.indexOf('{'), content.lastIndexOf('}') + 1)) }.getOrNull()
            ?: throw Fatal("The naming model did not return JSON.")
        var subject = clean(o.optString("subject"))
        var theme = clean(o.optString("theme"))
        if (subject.isEmpty()) subject = fallbackSubject(userSubjects, lang)
        subject = if (userSubjects.isNotEmpty()) {
            val m = Naming.matchSubject(subject, userSubjects)
            if (userSubjects.any { Naming.fold(it) == Naming.fold(m) }) m else fallbackSubject(userSubjects, lang)
        } else {
            Naming.matchSubject(subject, knownSubjects)
        }
        subject = subject.replaceFirstChar { it.uppercase() }.take(40)
        if (theme.isEmpty()) theme = if (lang == "it") "Varie" else "General"
        theme = theme.replaceFirstChar { it.uppercase() }.take(70)
        return subject to theme
    }

    private fun clean(s: String): String =
        Naming.fileSafe(s.replace(Regex("[\"“”«»]"), "")).trim('.', ',', ';', ' ')

    private fun fallbackSubject(userSubjects: List<String>, lang: String): String =
        userSubjects.firstOrNull { Naming.fold(it) in setOf("altro", "other", "varie", "misc") }
            ?: if (lang == "it" || lang.isEmpty()) "Altro" else "Other"

    companion object {
        /** Best first; a retired one is skipped. */
        val MODELS = listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b", "llama-3.1-8b-instant")
    }
}
