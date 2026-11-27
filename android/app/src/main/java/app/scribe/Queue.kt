package app.scribe

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
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
    /** Shorter than this is a pocket recording, not a lesson. */
    private const val MIN_RECORDING_MS = 3_000L

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

    /* ── organising the phone's recordings ── */

    class Organise(
        /** Transcribe and name every recording in [folders], including new ones as they appear. */
        val auto: Boolean = false,
        val folders: List<String> = emptyList(),
        /** The user's own subject list; when set, every recording goes into one of these. */
        val subjects: List<String> = emptyList(),
        val format: String = Naming.DEFAULT_FORMAT,
        /** Also rename the files themselves, so the recorder app shows the new names. */
        val renameFiles: Boolean = true,
    )

    @Volatile var organise = Organise()
        private set
    /** Bumped whenever any title changes, so the page knows to fetch them again. */
    @Volatile var namesVersion = 0
        private set

    private val classifier by lazy { Classifier({ SecureStore.get(app, KEY_NAME) }) }

    /** Names each finished file by subject and theme, and renumbers its subject. */
    private val organiser = object : PostProcessor {
        override fun enabled() = organise.auto
        override fun unavailable() = classifier.unavailable()
        override fun process(job: Job) {
            val text = store.segments(job).joinToString(" ") { it.text }
            val known = synchronized(store) {
                store.all().filter { it.naming == "done" && it.subject.isNotEmpty() }.map { it.subject }.distinctBy { Naming.fold(it) }
            }
            val (subject, theme) = classifier.classify(text, organise.subjects, known, settings.lang)
            synchronized(store) {
                if (store.get(job.id) == null) return
                job.subject = Naming.matchSubject(subject, known)
                job.theme = theme
                job.naming = "done"
                if (job.recordedAt == 0L) job.recordedAt = Naming.dateFromName(job.name) ?: job.createdAt
                val changed = Naming.renumber(store.all(), organise.format).toMutableSet()
                changed.add(job)
                for (j in changed) {
                    j.renamePending = organise.renameFiles && j.mediaUri.isNotEmpty() && j.appliedName != fileNameFor(j)
                    store.save(j)
                }
                namesVersion++
            }
            Diag.log("naming", "${job.name} -> ${job.title}")
            renameWithoutAsking()
        }
    }

    /** What the file on the phone should be called: the title, with the file's own extension. */
    fun fileNameFor(job: Job): String {
        val ext = job.name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 5 } ?: job.ext
        return if (job.title.isEmpty()) job.name else "${job.title}.$ext"
    }

    /** True when Scribe may rename recordings without the system asking each time. */
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

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
            materializer = { job -> materialize(job) },
            namer = organiser,
        )
        runner.listener = object : JobRunner.Listener {
            override fun onChange() { pageListener?.invoke(); serviceListener?.onChange() }
            override fun onFinished(job: Job) { pageListener?.invoke(); serviceListener?.onFinished(job) }
            override fun onIdle() { pageListener?.invoke(); serviceListener?.onIdle() }
        }
        if (organise.auto) watchLibrary()
    }

    fun hasUnfinished(): Boolean = store.all().any {
        !Status.finished(it.status) ||
            (organise.auto && it.status == Status.DONE && it.naming == "pending" && classifier.unavailable() == null)
    }

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
        var hiddenWaiting = 0
        val org = JSONObject()
        synchronized(store) {
            var pos = 0
            for (j in store.all()) {
                if (j.cleared) continue
                val position = if (j.status == Status.WAITING) ++pos else 0
                // A whole phone's backlog can be hundreds of files; the page
                // only needs the front of the line and a count of the rest.
                if (j.status == Status.WAITING && position > 25) { hiddenWaiting++; continue }
                jobs.put(j.summary(now, position))
            }
            val all = store.all()
            org.put("auto", organise.auto)
                .put("folders", JSONArray(organise.folders))
                .put("subjects", JSONArray(organise.subjects))
                .put("format", organise.format)
                .put("renameFiles", organise.renameFiles)
                .put("allFilesAccess", hasAllFilesAccess())
                .put("canAsk", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                .put("named", all.count { it.naming == "done" })
                .put("namingPending", all.count { it.naming == "pending" || (it.naming.isEmpty() && organise.auto && !Status.finished(it.status)) })
                .put("namingFailed", all.count { it.naming == "failed" })
                .put("renamePending", all.count { it.renamePending })
                .put("restorable", all.count { it.appliedName.isNotEmpty() && it.originalName.isNotEmpty() })
                .put("libraryQueued", all.count { it.mediaUri.isNotEmpty() && !Status.finished(it.status) })
                .put("namesVersion", namesVersion)
                .put("blocked", if (organise.auto) classifier.unavailable() ?: JSONObject.NULL else JSONObject.NULL)
        }
        return JSONObject()
            .put("jobs", jobs)
            .put("hiddenWaiting", hiddenWaiting)
            .put("organise", org)
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
        // A recording from the phone that the user deleted here stays deleted:
        // the next scan must not bring it back.
        store.get(id)?.mediaUri?.takeIf { it.isNotEmpty() }?.let { ignoreMedia(it) }
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
            if (!j.imported) {
                j.imported = true
                // Found on the phone rather than shared: it lives in Your
                // recordings now, and a backlog would bury the queue.
                if (j.mediaUri.isNotEmpty()) j.cleared = true
                store.save(j)
            }
        }
        pageListener?.invoke()
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
        loadOrganise()
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

    /* ── the phone's library ── */

    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun ignoredMedia(): Set<String> =
        runCatching { JSONArray(prefs().getString("ignoredMedia", "[]")).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } }
            .getOrDefault(emptySet())

    private fun ignoreMedia(uri: String) {
        prefs().edit().putString("ignoredMedia", JSONArray(ignoredMedia() + uri).toString()).apply()
    }

    /** Folders with audio in them, for the page to offer. */
    fun libraryFolders(context: Context): String {
        init(context)
        val known = synchronized(store) { store.all().mapNotNull { it.mediaUri.takeIf { u -> u.isNotEmpty() } }.toSet() }
        val arr = JSONArray()
        for ((folder, items) in RecorderLibrary.folders(context)) {
            arr.put(
                JSONObject()
                    .put("path", folder)
                    .put("count", items.size)
                    .put("totalMs", items.sumOf { it.durationMs })
                    .put("newCount", items.count { it.uri.toString() !in known })
                    .put("newMs", items.filter { it.uri.toString() !in known }.sumOf { it.durationMs })
                    .put("recorder", RecorderLibrary.looksLikeRecorder(folder))
                    .put("examples", JSONArray(items.take(3).map { it.name })),
            )
        }
        return arr.toString()
    }

    /**
     * Adds every recording in the chosen folders that the queue has not
     * seen, oldest first, so each subject is numbered in the order its
     * lectures happened. Nothing is copied yet; each is read when its turn
     * comes. @return how many were added.
     */
    @Synchronized
    fun syncLibrary(context: Context): Int {
        init(context)
        val o = organise
        if (!o.auto || o.folders.isEmpty()) return 0
        val ignored = ignoredMedia()
        val fresh = RecorderLibrary.items(context)
            .filter { it.folder in o.folders && it.durationMs >= MIN_RECORDING_MS }
            .filter { it.uri.toString() !in ignored && store.findByMedia(it.uri.toString()) == null }
            .sortedBy { it.recordedAt }
        for (item in fresh) {
            val job = Job(UUID.randomUUID().toString(), System.currentTimeMillis()).apply {
                name = item.name
                ext = item.name.substringAfterLast('.', "").lowercase()
                    .takeIf { it.length in 1..5 && it.all(Char::isLetterOrDigit) } ?: "m4a"
                size = item.size
                durationMs = item.durationMs
                mediaUri = item.uri.toString()
                recordedAt = item.recordedAt
            }
            store.add(job)
        }
        if (fresh.isNotEmpty()) {
            Diag.log("library", "${fresh.size} new recording(s) queued")
            pageListener?.invoke()
            runner.kick()
        }
        return fresh.size
    }

    /**
     * Copies a library recording in when its turn comes, fingerprinting it on
     * the way. If it is a file already transcribed — shared earlier, say —
     * the existing transcript is linked to it instead of doing it again.
     */
    private fun materialize(job: Job): Boolean {
        val uri = Uri.parse(job.mediaUri)
        val dest = store.source(job)
        val digest = MessageDigest.getInstance("SHA-256")
        val ok = runCatching {
            app.contentResolver.openInputStream(uri)?.use { raw ->
                DigestInputStream(raw, digest).use { input -> dest.outputStream().use { input.copyTo(it, 256 * 1024) } }
            } ?: return@runCatching false
            dest.length() > 0
        }.getOrDefault(false)
        if (!ok) {
            dest.delete()
            throw IllegalStateException("This recording is no longer on the phone, or Scribe was not allowed to read it.")
        }
        synchronized(store) {
            job.size = dest.length()
            job.hash = digest.digest().joinToString("") { "%02x".format(it) }
            val existing = store.findByHash(job.hash, job.id)
            if (existing != null) {
                Diag.log("library", "${job.name} was already transcribed as ${existing.name}")
                if (existing.mediaUri.isEmpty()) existing.mediaUri = job.mediaUri
                if (existing.recordedAt == 0L || job.recordedAt in 1 until existing.recordedAt) existing.recordedAt = job.recordedAt
                if (existing.naming.isEmpty() && existing.status == Status.DONE && organise.auto) existing.naming = "pending"
                store.save(existing)
                store.remove(job.id)
                return false
            }
            job.copied = true
            store.save(job)
        }
        return true
    }

    private var observer: ContentObserver? = null
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val rescan = Runnable { Thread { syncLibrary(app); if (hasUnfinished()) TranscribeService.start(app) }.start() }

    /** While the app's process is alive, new recordings are picked up within seconds. */
    private fun watchLibrary() {
        if (observer != null) return
        observer = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) {
                main.removeCallbacks(rescan)
                main.postDelayed(rescan, 8_000)
            }
        }
        runCatching {
            app.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer!!)
        }
        LibraryWatchJob.schedule(app)
    }

    private fun unwatchLibrary() {
        observer?.let { runCatching { app.contentResolver.unregisterContentObserver(it) } }
        observer = null
        LibraryWatchJob.cancel(app)
    }

    fun applyOrganise(context: Context, json: String) {
        init(context)
        val before = organise
        prefs().edit().putString("organise", json).apply()
        loadOrganise()
        val o = organise
        if (o.auto && !before.auto) {
            // Name what is already transcribed too, not just what comes next.
            synchronized(store) {
                for (j in store.all()) if (j.status == Status.DONE && j.naming.isEmpty()) { j.naming = "pending"; store.save(j) }
            }
        }
        if (o.format != before.format || o.renameFiles != before.renameFiles) {
            synchronized(store) {
                Naming.renumber(store.all(), o.format)
                for (j in store.all()) {
                    j.renamePending = o.renameFiles && j.mediaUri.isNotEmpty() && j.naming == "done" && j.appliedName != fileNameFor(j)
                    store.save(j)
                }
                namesVersion++
            }
        }
        if (o.auto) watchLibrary() else unwatchLibrary()
        Thread {
            syncLibrary(context)
            if (hasUnfinished()) TranscribeService.start(context)
            pageListener?.invoke()
        }.start()
    }

    private fun loadOrganise() {
        val o = runCatching { JSONObject(prefs().getString("organise", "{}") ?: "{}") }.getOrDefault(JSONObject())
        fun list(name: String) = o.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.getString(it).trim() }.filter { it.isNotEmpty() } } ?: emptyList()
        organise = Organise(
            auto = o.optBoolean("auto", false),
            folders = list("folders"),
            subjects = list("subjects"),
            format = o.optString("format", Naming.DEFAULT_FORMAT).ifBlank { Naming.DEFAULT_FORMAT },
            renameFiles = o.optBoolean("renameFiles", true),
        )
    }

    /** Every named job, for the page to update titles and subjects in Your recordings. */
    fun namedJobs(): String {
        val arr = JSONArray()
        synchronized(store) {
            for (j in store.all()) if (j.naming == "done") arr.put(
                JSONObject().put("id", j.id).put("title", j.title).put("subject", j.subject)
                    .put("number", j.number).put("theme", j.theme).put("recordedAt", j.recordedAt),
            )
        }
        return arr.toString()
    }

    /** Puts files whose naming failed back in line for naming. */
    fun retryNaming() {
        synchronized(store) { for (j in store.all()) if (j.naming == "failed") { j.naming = "pending"; store.save(j) } }
        runner.kick()
        if (hasUnfinished()) TranscribeService.start(app)
    }

    /* ── renaming the files on the phone ── */

    /** What each file should be renamed to: new names, or the originals back. */
    fun renameTargets(restore: Boolean): List<Pair<Job, String>> = synchronized(store) {
        if (restore) store.all().filter { it.appliedName.isNotEmpty() && it.originalName.isNotEmpty() }.map { it to it.originalName }
        else store.all().filter { it.renamePending && it.mediaUri.isNotEmpty() }.map { it to fileNameFor(it) }
    }

    /** Renames now, if Scribe is allowed to without asking. */
    fun renameWithoutAsking() {
        if (!hasAllFilesAccess() || !organise.renameFiles) return
        performRenames(renameTargets(false), restore = false)
    }

    /** Applies renames; the permission must already be there. @return how many worked. */
    fun performRenames(targets: List<Pair<Job, String>>, restore: Boolean): Int {
        var done = 0
        for ((job, name) in targets) {
            val uri = Uri.parse(job.mediaUri)
            val current = RecorderLibrary.currentName(app, uri)
            if (current == null) {
                synchronized(store) { job.renamePending = false; store.save(job) }
                continue
            }
            val ok = current == name || RecorderLibrary.rename(app, uri, name)
            synchronized(store) {
                if (ok) {
                    done++
                    if (restore) {
                        job.appliedName = ""
                        job.renamePending = false
                    } else {
                        if (job.originalName.isEmpty()) job.originalName = current.takeIf { it != name } ?: job.name
                        job.appliedName = name
                        job.renamePending = job.appliedName != fileNameFor(job)
                    }
                    store.save(job)
                }
            }
        }
        if (restore && done > 0) {
            // Restoring means "stop renaming my files".
            val o = runCatching { JSONObject(prefs().getString("organise", "{}") ?: "{}") }.getOrDefault(JSONObject())
            prefs().edit().putString("organise", o.put("renameFiles", false).toString()).apply()
            loadOrganise()
        }
        Diag.log("rename", "${if (restore) "restored" else "renamed"} $done of ${targets.size}")
        pageListener?.invoke()
        return done
    }

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
