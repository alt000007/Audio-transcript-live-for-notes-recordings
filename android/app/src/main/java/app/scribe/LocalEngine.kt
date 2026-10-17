package app.scribe

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File

/**
 * Whisper running on the phone itself, through whisper.cpp.
 *
 * Free with no limits and fully offline, but a phone is far slower than
 * Groq's servers and the models that fit on one are smaller, so it is less
 * accurate — noticeably so on Italian and on technical vocabulary. That is
 * why "auto" only falls back to it while Groq's free allowance is used up.
 *
 * The model is not bundled (it would make the app 60–190 MB); it is
 * downloaded once, from Settings, with Android's own download manager.
 */
class LocalEngine(private val context: Context) : Engine {
    override val id = "local"
    /** whisper.cpp already uses every core it is given; two at once just compete. */
    override val parallel = 1

    class Model(val id: String, val file: String, val sizeMb: Int, val label: String)

    private var loadedPath: String? = null
    private var handle = 0L

    private val prefs get() = context.getSharedPreferences("scribe-local", Context.MODE_PRIVATE)

    private fun modelDir(): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "models").apply { mkdirs() }

    fun chosenModel(): Model = MODELS.firstOrNull { it.id == prefs.getString("model", "small") } ?: MODELS[1]

    private fun modelFile(m: Model) = File(modelDir(), m.file)

    /** A model file that is all there. */
    private fun readyModel(): File? {
        val m = chosenModel()
        val f = modelFile(m)
        return if (f.exists() && f.length() > m.sizeMb * 1024L * 1024L * 9 / 10 && downloadProgress() == null) f else null
    }

    override fun unavailable(): String? = when {
        !libLoaded -> "The on-device engine is not included in this build"
        readyModel() == null -> "Download the on-device model in Settings to transcribe offline"
        else -> null
    }

    /** For the page: what is installed, and how a download is going. */
    fun status(): JSONObject {
        val m = chosenModel()
        val o = JSONObject()
            .put("supported", libLoaded)
            .put("model", m.id)
            .put("label", m.label)
            .put("sizeMb", m.sizeMb)
            .put("ready", readyModel() != null)
        downloadProgress()?.let { o.put("downloading", it) }
        return o
    }

    /** Starts downloading the chosen model. Safe to call twice. */
    fun download(modelId: String): Boolean {
        val m = MODELS.firstOrNull { it.id == modelId } ?: return false
        prefs.edit().putString("model", m.id).apply()
        if (downloadProgress() != null) return true
        val dest = modelFile(m)
        dest.delete()
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val req = DownloadManager.Request(Uri.parse(BASE_URL + m.file))
            .setTitle("Scribe offline model (${m.sizeMb} MB)")
            .setDescription("Lets Scribe transcribe without internet")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(dest))
            .setAllowedOverMetered(true)
        val id = runCatching { dm.enqueue(req) }.getOrElse {
            Diag.log("local", "download refused: ${it.message}")
            return false
        }
        prefs.edit().putLong("download", id).apply()
        return true
    }

    /** 0..1 while downloading, null when not. Clears a finished or failed download. */
    private fun downloadProgress(): Double? {
        val id = prefs.getLong("download", -1)
        if (id < 0) return null
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) { prefs.edit().remove("download").apply(); return null }
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) {
                if (status == DownloadManager.STATUS_FAILED) Diag.log("local", "model download failed")
                prefs.edit().remove("download").apply()
                return null
            }
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            return if (total > 0) done.toDouble() / total else 0.0
        }
        return null
    }

    @Synchronized
    fun deleteModel() {
        release()
        MODELS.forEach { modelFile(it).delete() }
    }

    /** Frees the model's memory; it is reloaded on the next piece. */
    @Synchronized
    fun release() {
        if (handle != 0L) { nativeFree(handle); handle = 0L; loadedPath = null }
    }

    @Synchronized
    override fun transcribe(audio: File, prompt: String, lang: String, cancelled: () -> Boolean): List<Seg> {
        val model = readyModel() ?: throw Fatal("The on-device model is not downloaded.")
        if (!libLoaded) throw Fatal("The on-device engine is not included in this build.")

        // Pieces are already 16 kHz mono AAC; decode them back to samples.
        val pcm = FloatArrayBuilder()
        var resampler: Resampler? = null
        var rateSeen = 0
        try {
            AudioDecoder.decode(audio, 0, cancelled) { s, n, rate ->
                if (resampler == null || rate != rateSeen) { resampler = Resampler(rate, 16_000); rateSeen = rate }
                resampler!!.push(s, n) { out, m -> pcm.add(out, m) }
            }
        } catch (e: Exception) {
            throw Fatal("This part could not be decoded for the on-device engine.")
        }
        if (cancelled()) return emptyList()

        if (loadedPath != model.absolutePath) {
            release()
            handle = nativeInit(model.absolutePath)
            if (handle == 0L) throw Fatal("The on-device model could not be loaded. Try downloading it again.")
            loadedPath = model.absolutePath
        }
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        val abort = AbortCheck(cancelled)
        val bytes = nativeTranscribe(handle, pcm.toArray(), lang.ifBlank { "auto" }, prompt, threads, abort)
            ?: if (cancelled()) return emptyList() else throw Fatal("The on-device engine failed on this part.")
        // Decoded leniently: a split multi-byte character becomes U+FFFD, not a crash.
        return String(bytes, Charsets.UTF_8).lineSequence().mapNotNull { line ->
            val p = line.split('\t', limit = 3)
            if (p.size < 3) return@mapNotNull null
            val text = p[2].trim()
            if (text.isEmpty()) null else Seg(p[0].toLong() * 10, p[1].toLong() * 10, text)
        }.toList()
    }

    /** Called from native code between steps; true stops the run. */
    class AbortCheck(private val cancelled: () -> Boolean) {
        @Suppress("unused") fun shouldAbort(): Boolean = cancelled()
    }

    private class FloatArrayBuilder {
        private var a = FloatArray(16_000 * 60)
        private var n = 0
        fun add(src: FloatArray, len: Int) {
            if (n + len > a.size) a = a.copyOf(maxOf(a.size * 2, n + len))
            System.arraycopy(src, 0, a, n, len)
            n += len
        }
        fun toArray(): FloatArray = a.copyOf(n)
    }

    private external fun nativeInit(modelPath: String): Long
    private external fun nativeFree(handle: Long)
    /** @return UTF-8, one line per segment: start and end in 10 ms units, then text, tab-separated. */
    private external fun nativeTranscribe(
        handle: Long, pcm: FloatArray, lang: String, prompt: String, threads: Int, abort: AbortCheck,
    ): ByteArray?

    companion object {
        private const val BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"

        val MODELS = listOf(
            Model("base", "ggml-base-q5_1.bin", 60, "Base — faster, less accurate"),
            Model("small", "ggml-small-q5_1.bin", 190, "Small — slower, more accurate"),
        )

        /**
         * The library is built for ARMv8.2 with dot-product and half-precision
         * instructions, which roughly doubles its speed. A phone without them
         * would crash on the first instruction, so check the CPU first and
         * simply go without the offline engine on one that lacks them.
         */
        val libLoaded: Boolean by lazy {
            if (!cpuSupported()) {
                Diag.log("local", "CPU lacks dotprod/fp16; offline engine unavailable")
                return@lazy false
            }
            runCatching { System.loadLibrary("scribewhisper") }
                .onFailure { Diag.log("local", "native library missing: ${it.message}") }
                .isSuccess
        }

        private fun cpuSupported(): Boolean {
            if (android.os.Build.SUPPORTED_ABIS.firstOrNull() != "arm64-v8a") return false
            val features = runCatching {
                File("/proc/cpuinfo").readLines().filter { it.startsWith("Features") }
                    .flatMap { it.substringAfter(':').trim().split(Regex("\\s+")) }.toSet()
            }.getOrDefault(emptySet())
            return "asimddp" in features && ("asimdhp" in features || "fphp" in features)
        }
    }
}
