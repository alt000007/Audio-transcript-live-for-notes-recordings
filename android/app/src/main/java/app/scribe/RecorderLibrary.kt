package app.scribe

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import kotlin.concurrent.thread

/**
 * The recordings already on the phone, as Android's media library knows them.
 *
 * Reading the library rather than one app's private folder means it works
 * with whichever recorder made them, and new recordings show up here on their
 * own. Recorders keep their files in a folder of their own ("MIUI/
 * sound_recorder", "Recordings", …), so the user picks which folders count.
 */
object RecorderLibrary {

    class Item(
        val id: Long,
        val name: String,
        val size: Long,
        val durationMs: Long,
        val addedMs: Long,
        val modifiedMs: Long,
        val folder: String,
    ) {
        val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

        /** The time in the file name if it has one, else the earliest file date. */
        val recordedAt: Long
            get() = Naming.dateFromName(name)
                ?: listOf(addedMs, modifiedMs).filter { it > 0 }.minOrNull()
                ?: 0L
    }

    /** Everything audio on the phone, newest first. */
    fun items(context: Context): List<Item> {
        val out = ArrayList<Item>()
        val folderCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Audio.Media.RELATIVE_PATH
                        else @Suppress("DEPRECATION") MediaStore.Audio.Media.DATA
        val cols = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED,
            folderCol,
        )
        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols, null, null,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val raw = c.getString(6) ?: ""
                    val folder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) raw
                                 else File(raw).parent?.substringAfter("/0/", "")?.plus("/") ?: ""
                    out.add(
                        Item(
                            c.getLong(0), c.getString(1) ?: "recording", c.getLong(2), c.getLong(3),
                            c.getLong(4) * 1000, c.getLong(5) * 1000, folder,
                        ),
                    )
                }
            }
        }.onFailure { Diag.log("library", "query failed: ${it.message}") }
        return out
    }

    /** Folders holding audio, the likeliest recorder folders first. */
    fun folders(context: Context): List<Pair<String, List<Item>>> =
        items(context).groupBy { it.folder }.toList()
            .sortedWith(compareByDescending<Pair<String, List<Item>>> { looksLikeRecorder(it.first) }.thenByDescending { it.second.size })

    fun looksLikeRecorder(folder: String): Boolean {
        val f = Naming.fold(folder)
        return listOf("record", "registr", "recorder", "voice", "memo", "dictaphone", "audio note").any { it in f }
    }

    /**
     * Renames a recording in the phone's library — which renames the file
     * itself. Only works with permission: "all files access", or a grant
     * from the system's "allow Scribe to modify these files?" dialog.
     */
    fun rename(context: Context, uri: Uri, newName: String): Boolean = try {
        val v = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) }
        context.contentResolver.update(uri, v, null, null) > 0
    } catch (e: Exception) {
        Diag.log("rename", "${e.javaClass.simpleName}: ${e.message}")
        false
    }

    /** The name the library has for it right now, or null if it is gone. */
    fun currentName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}

/**
 * Wakes when the media library changes — a new recording saved — even with
 * Scribe closed, and adds it to the queue.
 *
 * Android may not let an app in the background start its transcription
 * service; then a notification offers to start it with one tap instead, so a
 * new recording is never silently left untranscribed.
 */
class LibraryWatchJob : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        thread(name = "scribe-library-watch", isDaemon = true) {
            try {
                Queue.init(this)
                val added = Queue.syncLibrary(this)
                if (added > 0 && !TranscribeService.start(this)) TranscribeService.notifyNewRecordings(this, added)
            } catch (e: Throwable) {
                Diag.log("library", "watch failed: ${e.message}")
            } finally {
                jobFinished(params, false)
                // Content-change jobs fire once; set the next one up.
                schedule(this)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        private const val JOB_ID = 4711

        fun schedule(context: Context) {
            val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, LibraryWatchJob::class.java))
                .addTriggerContentUri(
                    JobInfo.TriggerContentUri(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS),
                )
                // A recorder writes a file in several steps; wait for it to settle.
                .setTriggerContentUpdateDelay(15_000)
                .setTriggerContentMaxDelay(120_000)
                .build()
            runCatching { js.schedule(job) }.onFailure { Diag.log("library", "could not schedule watch: ${it.message}") }
        }

        fun cancel(context: Context) {
            runCatching { (context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler).cancel(JOB_ID) }
        }
    }
}
