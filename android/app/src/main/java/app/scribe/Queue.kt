package app.scribe

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.concurrent.thread

/**
 * The file queue for the whole app process: one store, one runner, shared by
 * the activity (which adds files and draws the list) and the service (which
 * keeps the work going when the app is not on screen).
 */
object Queue {
    private const val PREFS = "scribe-queue"
    const val KEY_NAME = "groq-key"

    lateinit var store: JobStore
        private set
    lateinit var runner: JobRunner
        private set
    private lateinit var app: Context

    @Volatile var settings = QueueSettings()
        private set
    @Volatile var trimSilence = true
        private set
    @Volatile var notifyEach = false
        private set
    @Volatile var notifyBatch = true
        private set

    /** The activity, while it exists, to repaint the list. */
    @Volatile var pageListener: (() -> Unit)? = null
    /** The service, while it runs, to refresh its notification. */
    @Volatile var serviceListener: JobRunner.Listener? = null
    /** Tells the page a shared file was already transcribed (or is already queued). */
    @Volatile var duplicateListener: ((name: String, existing: Job) -> Unit)? = null

    val local: LocalEngine by lazy { LocalEngine(app) }

    @Synchronized
    fun init(context: Context) {
        if (::store.isInitialized) return
        app = context.applicationContext
        loadSettings()
        store = JobStore(File(app.filesDir, "jobs"))
        store.load()
        runner = JobRunner(
            store,
            AudioPreparer { trimSilence },
            mapOf("groq" to GroqEngine({ SecureStore.get(app, KEY_NAME) }), "local" to local),
            { settings },
        )
        runner.listener = object : JobRunner.Listener {
            override fun onChange() { pageListener?.invoke(); serviceListener?.onChange() }
            override fun onFinished(job: Job) { pageListener?.invoke(); serviceListener?.onFinished(job) }
            override fun onIdle() { pageListener?.invoke(); serviceListener?.onIdle() }
        }
    }

    fun hasUnfinished(): Boolean = store.all().any { !Status.finished(it.status) }

    /** Starts the background service if there is anything to do. */
    fun resume(context: Context) {
        init(context)
        if (hasUnfinished()) TranscribeService.start(context)
    }

    /**
     * Copies shared or picked files into the queue.
     *
     * Each file appears in the list the moment it arrives, marked as copying,
     * and is copied into the app's own storage straight away — the permission
     * a share grants to read a file does not outlive the app, so this is the
     * one step that cannot wait. A fingerprint of the content is taken during
     * the copy; a file already transcribed is not done twice.
     */
    fun addFiles(context: Context, uris: List<Uri>) {
        init(context)
        if (uris.isEmpty()) return
        val created = uris.map { uri ->
            val name = displayName(context, uri)
            val ext = name.substringAfterLast('.', "").lowercase()
                .takeIf { it.length in 1..5 && it.all(Char::isLetterOrDigit) } ?: "m4a"
            val job = Job(UUID.randomUUID().toString(), System.currentTimeMillis()).apply {
                this.name = name
                this.ext = ext
            }
            store.add(job)
            uri to job
        }
        pageListener?.invoke()
        TranscribeService.start(context)

        thread(name = "scribe-copy", isDaemon = true) {
            for ((uri, job) in created) {
                val dest = store.source(job)
                val digest = MessageDigest.getInstance("SHA-256")
                val ok = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { raw ->
                        DigestInputStream(raw, digest).use { input ->
                            dest.outputStream().use { out -> input.copyTo(out, 256 * 1024) }
                        }
                    } ?: return@runCatching false
                    dest.length() > 0
                }.getOrDefault(false)

                synchronized(store) {
                    if (!ok) {
                        dest.delete()
                        job.status = Status.FAILED
                        job.error = "Could not read this file. The app it came from may not have shared it fully."
                        job.copied = true   // nothing more will arrive
                        store.save(job)
                        Diag.log("copy", "failed: ${job.name}")
                        return@synchronized
                    }
                    job.size = dest.length()
                    job.hash = digest.digest().joinToString("") { "%02x".format(it) }
                    val existing = store.findByHash(job.hash, job.id)
                    if (existing != null) {
                        Diag.log("copy", "duplicate of ${existing.name}: ${job.name}")
                        store.remove(job.id)
                        if (existing.status == Status.FAILED || existing.status == Status.CANCELLED) {
                            runner.retry(existing.id)
                        } else if (existing.cleared) {
                            existing.cleared = false
                            store.save(existing)
                        }
                        duplicateListener?.invoke(job.name, existing)
                        return@synchronized
                    }
                    job.copied = true
                    store.save(job)
                    Diag.log("copy", "${job.name}: ${job.size} bytes")
                }
                pageListener?.invoke()
                runner.kick()
            }
            pageListener?.invoke()
            runner.kick()
        }
    }

    fun snapshot(): String {
        init(app)
        val now = System.currentTimeMillis()
        val jobs = JSONArray()
        synchronized(store) {
            var pos = 0
            for (j in store.all()) {
                if (j.cleared) continue
                val position = if (j.status == Status.WAITING) ++pos else 0
                jobs.put(j.summary(now, position))
            }
        }
        return JSONObject()
            .put("jobs", jobs)
            .put("running", runner.isRunning)
            .put("waitUntil", runner.waitUntil)
            .put("blocked", runner.blockedNote ?: JSONObject.NULL)
            .put("settings", settingsJson())
            .put("local", local.status())
            .toString()
    }

    fun segmentsJson(id: String): String {
        val job = store.get(id) ?: return "[]"
        val arr = JSONArray()
        store.segments(job).forEach { arr.put(it.toJson()) }
        return arr.toString()
    }

    fun move(id: String, delta: Int) { if (store.move(id, delta)) { pageListener?.invoke(); runner.kick() } }

    fun remove(id: String) {
        runner.cancel(id)
        store.remove(id)
        pageListener?.invoke()
    }

    /** Hides finished jobs from the list; they stay known so a re-share is caught. */
    fun clearFinished() {
        synchronized(store) {
            for (j in store.all()) if (j.status == Status.DONE && !j.cleared) { j.cleared = true; store.save(j) }
        }
        pageListener?.invoke()
    }

    fun markImported(id: String) {
        synchronized(store) {
            val j = store.get(id) ?: return
            if (!j.imported) { j.imported = true; store.save(j) }
        }
    }

    fun setKey(context: Context, key: String): Boolean {
        init(context)
        val ok = SecureStore.put(context, KEY_NAME, key.trim())
        runner.keyChanged()
        if (key.isNotBlank()) resume(context)
        return ok
    }

    fun getKey(context: Context): String = SecureStore.get(context, KEY_NAME) ?: ""

    fun applySettings(context: Context, json: String) {
        init(context)
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("settings", json).apply()
        loadSettings()
        runner.kick()
        if (hasUnfinished()) resume(context)
    }

    private fun loadSettings() {
        val o = runCatching {
            JSONObject(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("settings", "{}") ?: "{}")
        }.getOrDefault(JSONObject())
        settings = QueueSettings(
            filesAtOnce = o.optInt("filesAtOnce", 2).coerceIn(1, 4),
            uploadsAtOnce = o.optInt("uploadsAtOnce", 3).coerceIn(1, 6),
            engine = o.optString("engine", "auto").takeIf { it in listOf("auto", "groq", "local") } ?: "auto",
            lang = o.optString("lang", ""),
            glossary = o.optString("glossary", ""),
            keepAudio = o.optBoolean("keepAudio", false),
        )
        trimSilence = o.optBoolean("trimSilence", true)
        notifyEach = o.optBoolean("notifyEach", false)
        notifyBatch = o.optBoolean("notifyBatch", true)
    }

    private fun settingsJson(): JSONObject = JSONObject()
        .put("filesAtOnce", settings.filesAtOnce)
        .put("uploadsAtOnce", settings.uploadsAtOnce)
        .put("engine", settings.engine)
        .put("trimSilence", trimSilence)
        .put("notifyEach", notifyEach)
        .put("notifyBatch", notifyBatch)
        .put("keepAudio", settings.keepAudio)

    fun displayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) return c.getString(i) ?: "recording"
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "recording"
    }
}
