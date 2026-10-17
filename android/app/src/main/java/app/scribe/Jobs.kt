package app.scribe

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The file queue's state, kept on disk so nothing depends on the app staying
 * open.
 *
 * Before, a shared batch lived only in the page's memory and the copies sat in
 * the cache directory, which Android clears when it likes: closing the app, a
 * crash or a reboot lost the whole batch and every file had to be shared
 * again. Now each file is copied into the app's own storage the moment it
 * arrives, and every change of state is written down as it happens.
 *
 * Layout, under filesDir/jobs:
 *   order.json             the queue order, as a list of job ids
 *   <id>/job.json          one file's state, including its chunk list
 *   <id>/source.<ext>      the copy of the shared file (removed once done)
 *   <id>/chunks/cN.m4a     prepared pieces of audio
 *   <id>/chunks/cN.json    each piece's transcript, saved the moment it lands
 *   <id>/result.json       the finished transcript
 *
 * Every write goes to a temporary file first and is then renamed over the
 * old one, so a crash mid-write leaves the previous version, never half a
 * file.
 */

object Status {
    const val WAITING = "waiting"
    const val PROCESSING = "processing"
    const val DONE = "done"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"

    fun finished(s: String) = s == DONE || s == FAILED || s == CANCELLED
}

/** One transcribed stretch of speech, in milliseconds from the start of the original file. */
class Seg(val startMs: Long, val endMs: Long, val text: String) {
    fun toJson(): JSONObject = JSONObject().put("s", startMs).put("e", endMs).put("t", text)

    companion object {
        fun fromJson(o: JSONObject) = Seg(o.optLong("s"), o.optLong("e"), o.optString("t"))
    }
}

/**
 * A piece of one file, sized to upload and transcribe on its own.
 *
 * [map] ties the piece back to the original: silences are shortened before
 * upload, so a time inside the piece is not the same as a time in the
 * recording. It holds pairs of (time in piece, time in original), each
 * starting a stretch where the two advance together.
 */
class Chunk(
    val index: Int,
    val file: String,
    val startMs: Long,
    val endMs: Long,
    val map: LongArray,
) {
    var status = "pending"      // pending | working | done | failed
    var attempts = 0
    var error: String? = null
    var engine: String? = null

    /** Not persisted: when this piece may next be tried after a network failure. */
    var notBefore = 0L
    var offlineTries = 0

    fun toOriginal(ms: Long): Long {
        var i = 0
        while (i + 2 < map.size && map[i + 2] <= ms) i += 2
        return if (map.size >= 2) map[i + 1] + (ms - map[i]) else startMs + ms
    }

    fun toJson(): JSONObject = JSONObject()
        .put("index", index).put("file", file)
        .put("startMs", startMs).put("endMs", endMs)
        .put("map", JSONArray().apply { map.forEach { put(it) } })
        .put("status", status).put("attempts", attempts)
        .put("error", error ?: JSONObject.NULL)
        .put("engine", engine ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject): Chunk {
            val m = o.optJSONArray("map") ?: JSONArray()
            return Chunk(
                o.getInt("index"), o.getString("file"),
                o.optLong("startMs"), o.optLong("endMs"),
                LongArray(m.length()) { m.getLong(it) },
            ).apply {
                status = o.optString("status", "pending")
                attempts = o.optInt("attempts")
                error = o.optString("error").takeIf { o.has("error") && !o.isNull("error") }
                engine = o.optString("engine").takeIf { o.has("engine") && !o.isNull("engine") }
            }
        }
    }
}

class Job(val id: String, val createdAt: Long) {
    var name = "recording"
    var ext = "m4a"
    var hash = ""
    var size = 0L
    var status = Status.WAITING
    var error: String? = null

    /** False until the shared file is fully copied in; only then can it be worked on. */
    var copied = false
    /** True once every chunk is known. Until then more may still be added. */
    var prepared = false
    /** How far into the original preparation has reached, so it can resume there. */
    var preparedToMs = 0L
    /** "trim" when the audio was decoded and cleaned up; "raw" when it is sent as it came. */
    var mode = ""

    var durationMs = 0L
    var keptMs = 0L
    var startedAt = 0L
    var finishedAt = 0L
    /** Time actually spent working on this file, summed across app restarts. */
    var activeMs = 0L

    /** Copied into the page's library; kept here afterwards for duplicate detection. */
    var imported = false
    /** Hidden from the queue list, still remembered for duplicate detection. */
    var cleared = false

    val chunks = ArrayList<Chunk>()

    /** Not persisted: what is happening right now, in words, for the list. */
    var note = ""
    var sessionStart = 0L

    fun chunksDone() = chunks.count { it.status == "done" }

    fun liveActiveMs(now: Long) = activeMs + if (sessionStart > 0) now - sessionStart else 0

    fun engines(): String = chunks.mapNotNull { it.engine }.distinct().sorted().joinToString("+")

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("createdAt", createdAt)
        .put("name", name).put("ext", ext).put("hash", hash).put("size", size)
        .put("status", status).put("error", error ?: JSONObject.NULL)
        .put("copied", copied).put("prepared", prepared).put("preparedToMs", preparedToMs)
        .put("mode", mode)
        .put("durationMs", durationMs).put("keptMs", keptMs)
        .put("startedAt", startedAt).put("finishedAt", finishedAt).put("activeMs", activeMs)
        .put("imported", imported).put("cleared", cleared)
        .put("chunks", JSONArray().apply { chunks.forEach { put(it.toJson()) } })

    /** What the page needs to draw the list — no transcript text. */
    fun summary(now: Long, position: Int): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("createdAt", createdAt).put("size", size)
        .put("status", status).put("error", error ?: JSONObject.NULL)
        .put("note", note).put("position", position)
        .put("copied", copied).put("prepared", prepared)
        .put("chunksDone", chunksDone()).put("chunksTotal", chunks.size)
        .put("chunksFailed", chunks.count { it.status == "failed" })
        .put("durationMs", durationMs).put("keptMs", keptMs)
        .put("preparedToMs", preparedToMs)
        .put("startedAt", startedAt).put("finishedAt", finishedAt)
        .put("processingMs", liveActiveMs(now))
        .put("engine", engines())
        .put("imported", imported)

    companion object {
        fun fromJson(o: JSONObject): Job = Job(o.getString("id"), o.optLong("createdAt")).apply {
            name = o.optString("name", "recording")
            ext = o.optString("ext", "m4a")
            hash = o.optString("hash")
            size = o.optLong("size")
            status = o.optString("status", Status.WAITING)
            error = o.optString("error").takeIf { o.has("error") && !o.isNull("error") }
            copied = o.optBoolean("copied")
            prepared = o.optBoolean("prepared")
            preparedToMs = o.optLong("preparedToMs")
            mode = o.optString("mode")
            durationMs = o.optLong("durationMs")
            keptMs = o.optLong("keptMs")
            startedAt = o.optLong("startedAt")
            finishedAt = o.optLong("finishedAt")
            activeMs = o.optLong("activeMs")
            imported = o.optBoolean("imported")
            cleared = o.optBoolean("cleared")
            val arr = o.optJSONArray("chunks") ?: JSONArray()
            for (i in 0 until arr.length()) chunks.add(Chunk.fromJson(arr.getJSONObject(i)))
        }
    }
}

/**
 * Reads and writes the queue. Every method is synchronized on the store, and
 * callers that change a [Job] do so inside `synchronized(store)` and then
 * call [save], so what is on disk is always a state that really existed.
 */
class JobStore(val root: File) {

    private val jobs = LinkedHashMap<String, Job>()

    init {
        root.mkdirs()
    }

    fun dir(id: String) = File(root, safe(id))
    fun chunkDir(id: String) = File(dir(id), "chunks")
    fun source(job: Job) = File(dir(job.id), "source.${job.ext}")
    fun chunkAudio(job: Job, c: Chunk) = File(dir(job.id), c.file)
    private fun chunkResult(job: Job, index: Int) = File(chunkDir(job.id), "c$index.json")
    private fun resultFile(job: Job) = File(dir(job.id), "result.json")

    /**
     * Loads everything from disk and puts interrupted work back in line.
     *
     * Anything that was mid-flight when the process died is simply waiting
     * again: a chunk that was uploading goes back to pending, and its
     * finished neighbours keep their saved text.
     */
    @Synchronized
    fun load(): List<Job> {
        jobs.clear()
        val order = readJson(File(root, "order.json"))?.optJSONArray("order") ?: JSONArray()
        val ids = LinkedHashSet<String>()
        for (i in 0 until order.length()) ids.add(order.getString(i))
        // Directories that order.json does not mention yet (a crash between
        // writing the job and the order) still belong in the queue.
        root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.lastModified() }?.forEach { ids.add(it.name) }

        for (id in ids) {
            val o = readJson(File(dir(id), "job.json")) ?: continue
            val job = runCatching { Job.fromJson(o) }.getOrNull() ?: continue
            var changed = false
            if (!job.copied && !Status.finished(job.status)) {
                // The share's permission to read the file ended with the old
                // process, so this one cannot be finished. Say so plainly.
                job.status = Status.FAILED
                job.error = "The app closed before this file was fully copied in. Share it again."
                changed = true
            }
            if (job.status == Status.PROCESSING) { job.status = Status.WAITING; changed = true }
            for (c in job.chunks) {
                if (c.status == "working") { c.status = "pending"; changed = true }
                // A result file that made it to disk wins over a stale status.
                if (c.status != "done" && chunkResult(job, c.index).exists()) { c.status = "done"; changed = true }
            }
            jobs[job.id] = job
            if (changed) writeJob(job)
        }
        writeOrder()
        return jobs.values.toList()
    }

    @Synchronized fun all(): List<Job> = jobs.values.toList()
    @Synchronized fun get(id: String): Job? = jobs[id]

    @Synchronized
    fun add(job: Job) {
        jobs[job.id] = job
        dir(job.id).mkdirs()
        writeJob(job)
        writeOrder()
    }

    @Synchronized
    fun save(job: Job) {
        if (jobs.containsKey(job.id)) writeJob(job)
    }

    /** Moves a job [delta] places up (negative) or down the queue. */
    @Synchronized
    fun move(id: String, delta: Int): Boolean {
        val list = jobs.keys.toMutableList()
        val from = list.indexOf(id)
        if (from < 0) return false
        val to = (from + delta).coerceIn(0, list.size - 1)
        if (to == from) return false
        list.add(to, list.removeAt(from))
        val copy = LinkedHashMap(jobs)
        jobs.clear()
        list.forEach { jobs[it] = copy.getValue(it) }
        writeOrder()
        return true
    }

    @Synchronized
    fun remove(id: String) {
        jobs.remove(id) ?: return
        writeOrder()
        dir(id).deleteRecursively()
    }

    @Synchronized
    fun findByHash(hash: String, except: String): Job? =
        if (hash.isEmpty()) null else jobs.values.firstOrNull { it.id != except && it.hash == hash }

    /** Saves one chunk's transcript the moment it lands, then marks it done. */
    @Synchronized
    fun saveChunkResult(job: Job, chunk: Chunk, segs: List<Seg>) {
        // Removed while this part was transcribing: do not bring it back.
        if (!jobs.containsKey(job.id)) return
        chunkDir(job.id).mkdirs()
        writeAtomic(chunkResult(job, chunk.index), JSONObject()
            .put("segments", JSONArray().apply { segs.forEach { put(it.toJson()) } }).toString())
        chunk.status = "done"
        chunk.error = null
        writeJob(job)
    }

    @Synchronized
    fun chunkSegments(job: Job, index: Int): List<Seg> {
        val arr = readJson(chunkResult(job, index))?.optJSONArray("segments") ?: return emptyList()
        return (0 until arr.length()).map { Seg.fromJson(arr.getJSONObject(it)) }
    }

    /** Everything transcribed so far, in order — the finished result, or the partial one. */
    @Synchronized
    fun segments(job: Job): List<Seg> {
        readJson(resultFile(job))?.optJSONArray("segments")?.let { arr ->
            return (0 until arr.length()).map { Seg.fromJson(arr.getJSONObject(it)) }
        }
        return job.chunks.filter { it.status == "done" }.sortedBy { it.index }
            .flatMap { chunkSegments(job, it.index) }
    }

    /** Writes the assembled transcript and drops the audio it no longer needs. */
    @Synchronized
    fun finish(job: Job, keepAudio: Boolean) {
        if (!jobs.containsKey(job.id)) return
        val segs = job.chunks.sortedBy { it.index }.flatMap { chunkSegments(job, it.index) }
        writeAtomic(resultFile(job), JSONObject()
            .put("segments", JSONArray().apply { segs.forEach { put(it.toJson()) } }).toString())
        if (!keepAudio) {
            source(job).delete()
            job.chunks.forEach { c -> chunkAudio(job, c).takeIf { it.name != source(job).name }?.delete() }
        }
        writeJob(job)
    }

    private fun writeJob(job: Job) {
        dir(job.id).mkdirs()
        writeAtomic(File(dir(job.id), "job.json"), job.toJson().toString())
    }

    private fun writeOrder() {
        writeAtomic(File(root, "order.json"), JSONObject().put("order", JSONArray(jobs.keys.toList())).toString())
    }

    companion object {
        /** Ids come back through JavaScript; never let one name a path outside the store. */
        fun safe(id: String) = id.filter { it.isLetterOrDigit() || it == '-' }.ifEmpty { "x" }

        fun readJson(f: File): JSONObject? =
            if (!f.exists()) null else runCatching { JSONObject(f.readText()) }.getOrNull()

        /** Temp file, flushed to the disk, then renamed over the original. */
        fun writeAtomic(f: File, text: String) {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            try {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: Exception) {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
