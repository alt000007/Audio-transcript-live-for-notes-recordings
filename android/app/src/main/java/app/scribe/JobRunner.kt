package app.scribe

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** A transcriber the queue can hand a piece of audio to. */
interface Engine {
    val id: String
    /** How many pieces it can usefully take at once. */
    val parallel: Int
    /** Null when ready to use; otherwise why it is not, in words. */
    fun unavailable(): String?
    /** Segment times are relative to the start of [audio]. */
    fun transcribe(audio: File, prompt: String, lang: String, cancelled: () -> Boolean): List<Seg>
}

/** The service says to wait. Not a failure: the piece goes back in line. */
class RateLimited(val waitMs: Long, message: String) : Exception(message)
/** Network gone, or the service hiccupped. Retried for as long as it takes. */
class Transient(message: String) : Exception(message)
/** The key itself was refused, so nothing online will work until it changes. */
class KeyRejected(message: String) : Exception(message)
/** This piece cannot be transcribed as it is. */
class Fatal(message: String) : Exception(message)

/** Turns a copied file into chunks, adding each to the job as it is made. */
interface Preparer {
    fun prepare(job: Job, store: JobStore, onChunk: (Chunk) -> Unit, cancelled: () -> Boolean)
}

class QueueSettings(
    /** Files worked on at the same time. */
    val filesAtOnce: Int = 2,
    /** Pieces transcribing at the same time, across all files. */
    val uploadsAtOnce: Int = 3,
    /** "auto", "groq" or "local". */
    val engine: String = "auto",
    val lang: String = "",
    val glossary: String = "",
    val keepAudio: Boolean = false,
)

/**
 * Works through the queue: prepares files into chunks, transcribes chunks in
 * parallel, and saves every result the moment it exists.
 *
 * Several files are in progress at once, but the one nearest the front of the
 * queue always gets first claim on a free slot, so files finish in the order
 * they were shared and the first is readable long before the last has
 * started. That is the fix for "I only see anything once all of them are done".
 *
 * Nothing here touches Android, so the scheduling can be exercised on a
 * desktop JVM with fake engines.
 *
 * Locking: the store's monitor guards job state; [lock] guards the runner's
 * own bookkeeping. Only the scheduler thread ever holds both, and always takes
 * the store first.
 */
class JobRunner(
    private val store: JobStore,
    private val preparer: Preparer,
    private val engines: Map<String, Engine>,
    private val settings: () -> QueueSettings,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    interface Listener {
        fun onChange()
        fun onFinished(job: Job)
        fun onIdle()
    }

    @Volatile var listener: Listener? = null

    private val lock = Object()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "scribe-queue").apply { isDaemon = true } }
    @Volatile private var running = false

    private val preparing = HashSet<String>()
    private val cancelFlags = HashMap<String, AtomicBoolean>()
    private var inFlight = 0

    /** Until when the online engine has asked everyone to wait. Limits are per account, not per file. */
    @Volatile var waitUntil = 0L
        private set
    /** The key was refused; set until it changes. */
    @Volatile private var keyProblem: String? = null
    /** Why nothing can run right now, if that is the case. */
    @Volatile var blockedNote: String? = null
        private set

    val isRunning get() = running

    fun start() {
        synchronized(lock) {
            if (running) { lock.notifyAll(); return }
            running = true
            cancelFlags.clear()
            Thread({ runLoop() }, "scribe-queue-loop").apply { isDaemon = true; start() }
        }
    }

    /** Stops scheduling. Work in flight is abandoned and picked up again next time. */
    fun stop() {
        synchronized(lock) {
            running = false
            cancelFlags.values.forEach { it.set(true) }
            lock.notifyAll()
        }
        synchronized(store) {
            for (job in store.all()) if (job.sessionStart > 0) {
                closeSession(job)
                store.save(job)
            }
        }
    }

    /** Something changed — a new file, a new key, a setting — so look again. */
    fun kick() {
        synchronized(lock) { lock.notifyAll() }
    }

    /** Clears a block caused by the key, once the key has changed. */
    fun keyChanged() {
        keyProblem = null
        waitUntil = 0
        kick()
    }

    fun cancel(id: String) {
        synchronized(store) {
            val job = store.get(id) ?: return
            if (Status.finished(job.status)) return
            closeSession(job)
            job.status = Status.CANCELLED
            job.note = ""
            store.save(job)
        }
        synchronized(lock) { cancelFlags[id]?.set(true) }
        changed()
        kick()
    }

    /** Puts a failed or cancelled job back in line. Finished pieces are kept. */
    fun retry(id: String) {
        synchronized(store) {
            val job = store.get(id) ?: return
            if (job.status != Status.FAILED && job.status != Status.CANCELLED) return
            if (!job.copied) return
            job.status = Status.WAITING
            job.error = null
            for (c in job.chunks) if (c.status == "failed") { c.status = "pending"; c.attempts = 0; c.error = null }
            store.save(job)
        }
        synchronized(lock) { cancelFlags.remove(id) }
        changed()
        kick()
    }

    private fun changed() { listener?.onChange() }

    private fun closeSession(job: Job) {
        if (job.sessionStart > 0) {
            job.activeMs += clock() - job.sessionStart
            job.sessionStart = 0
        }
    }

    private fun runLoop() {
        while (true) {
            if (!running) return
            val next = try {
                schedule()
            } catch (e: Throwable) {
                Diag.log("queue", "schedule error: ${e.message}")
                Pass.WAIT
            }
            if (next == Pass.IDLE) {
                synchronized(lock) { running = false }
                listener?.onIdle()
                return
            }
            // A file just finished, which frees a place: look again at once.
            if (next == Pass.AGAIN) continue
            synchronized(lock) { if (running) lock.wait(1000) }
        }
    }

    private enum class Pass { IDLE, AGAIN, WAIT }

    /** One pass of the scheduler. */
    private fun schedule(): Pass {
        val s = settings()
        val now = clock()
        var anyChange = false
        val finished = ArrayList<Job>()

        val window: List<Job>
        synchronized(store) {
            val all = store.all()
            val open = all.filter { !Status.finished(it.status) && it.copied }
            val copying = all.any { !Status.finished(it.status) && !it.copied }
            if (open.isEmpty()) {
                val busy = synchronized(lock) { preparing.isNotEmpty() || inFlight > 0 }
                return if (!busy && !copying) Pass.IDLE else Pass.WAIT
            }
            window = open.take(s.filesAtOnce.coerceIn(1, 4))

            // Finish anything whose every piece is accounted for.
            for (job in window) {
                if (!job.prepared) continue
                if (job.chunks.any { it.status == "pending" || it.status == "working" }) continue
                if (synchronized(lock) { preparing.contains(job.id) }) continue
                finishJob(job, s)
                finished.add(job)
                anyChange = true
            }
        }
        finished.forEach { listener?.onFinished(it) }

        // Start preparing the files at the front that are not yet cut up.
        for (job in window) {
            if (job.prepared || Status.finished(job.status)) continue
            val flag = synchronized(lock) {
                if (preparing.contains(job.id)) null
                else AtomicBoolean(false).also { preparing.add(job.id); cancelFlags[job.id] = it }
            } ?: continue
            startSession(job)
            pool.execute { prepareJob(job, flag) }
            anyChange = true
        }

        // Hand out slots, front of the queue first.
        val engine = pickEngine(s, now)
        if (updateNotes(window, engine, now)) anyChange = true
        if (engine != null) {
            val limit = minOf(s.uploadsAtOnce.coerceIn(1, 6), engine.parallel)
            while (true) {
                if (synchronized(lock) { inFlight >= limit }) break
                val (job, chunk) = synchronized(store) { nextChunk(window, now) } ?: break
                synchronized(store) {
                    chunk.status = "working"
                    chunk.engine = engine.id
                    store.save(job)
                }
                startSession(job)
                val flag = synchronized(lock) {
                    inFlight++
                    cancelFlags.getOrPut(job.id) { AtomicBoolean(false) }
                }
                anyChange = true
                pool.execute { runChunk(job, chunk, engine, s, flag) }
            }
        }
        if (anyChange) changed()
        return if (finished.isNotEmpty()) Pass.AGAIN else Pass.WAIT
    }

    private fun startSession(job: Job) {
        synchronized(store) {
            if (job.status == Status.WAITING) {
                job.status = Status.PROCESSING
                if (job.startedAt == 0L) job.startedAt = clock()
                store.save(job)
            }
            if (job.sessionStart == 0L) job.sessionStart = clock()
        }
    }

    /**
     * Which engine gets the next piece, or null if none can run right now.
     *
     * "auto" prefers Groq — far faster and more accurate than anything a phone
     * can run — and falls back to the on-device model only while Groq is out
     * of allowance for a long stretch, or cannot be used at all.
     */
    private fun pickEngine(s: QueueSettings, now: Long): Engine? {
        val groq = engines["groq"]
        val local = engines["local"]
        val groqReason = keyProblem ?: groq?.unavailable() ?: if (groq == null) "no online engine" else null
        val localReason = local?.unavailable() ?: if (local == null) "the on-device engine is not available" else null
        val groqWaiting = now < waitUntil
        // A wait this long means the hourly or daily allowance is gone, and the
        // phone is worth using in the meantime. A short one is just waited out.
        val longWait = waitUntil - now > LONG_WAIT_MS

        val choice: Engine? = when (s.engine) {
            "groq" -> if (groqReason == null && !groqWaiting) groq else null
            "local" -> if (localReason == null) local else null
            else -> when {
                groqReason == null && !groqWaiting -> groq
                localReason == null && (groqReason != null || longWait) -> local
                else -> null
            }
        }
        blockedNote = when {
            choice != null || groqWaiting && s.engine != "local" -> null
            s.engine == "groq" -> groqReason
            s.engine == "local" -> localReason
            else -> groqReason
        }
        return choice
    }

    /** The waiting reason shown on each line. @return true if any changed. */
    private fun updateNotes(window: List<Job>, engine: Engine?, now: Long): Boolean {
        var changed = false
        synchronized(store) {
            for (job in store.all()) {
                val note = when {
                    Status.finished(job.status) -> ""
                    !job.copied -> "Copying into the app…"
                    job !in window -> "Waiting"
                    engine == null && now < waitUntil ->
                        "Waiting for the rate limit — ${humanWait(waitUntil - now)}"
                    engine == null && blockedNote != null -> blockedNote!!
                    job.chunks.any { it.status == "pending" && it.notBefore > now } &&
                        job.chunks.none { it.status == "working" } -> "No connection — retrying"
                    !job.prepared && job.chunks.isEmpty() -> "Preparing the audio…"
                    else -> ""
                }
                if (note != job.note) { job.note = note; changed = true }
            }
        }
        return changed
    }

    private fun nextChunk(window: List<Job>, now: Long): Pair<Job, Chunk>? {
        for (job in window) {
            if (Status.finished(job.status)) continue
            val c = job.chunks.firstOrNull { it.status == "pending" && it.notBefore <= now } ?: continue
            return job to c
        }
        return null
    }

    private fun prepareJob(job: Job, flag: AtomicBoolean) {
        var failedOutright = false
        try {
            preparer.prepare(job, store, { chunk ->
                synchronized(store) {
                    job.chunks.add(chunk)
                    store.save(job)
                }
                changed()
                kick()
            }, { flag.get() || !running })
            synchronized(store) {
                if (!flag.get() && running) {
                    job.prepared = true
                    if (job.chunks.isEmpty()) {
                        job.status = Status.FAILED
                        job.error = "No audio could be read from this file."
                        closeSession(job)
                        failedOutright = true
                    }
                    store.save(job)
                }
            }
        } catch (e: Throwable) {
            Diag.log("prepare", "${job.name}: ${e.message}")
            synchronized(store) {
                if (job.chunks.isEmpty()) {
                    job.status = Status.FAILED
                    job.error = e.message ?: "This file could not be read."
                    closeSession(job)
                    failedOutright = true
                } else {
                    // Keep what was prepared; a damaged tail should not cost the rest.
                    job.prepared = true
                }
                store.save(job)
            }
        } finally {
            synchronized(lock) { preparing.remove(job.id) }
            if (failedOutright) listener?.onFinished(job)
            changed()
            kick()
        }
    }

    private fun promptFor(job: Job, chunk: Chunk, s: QueueSettings): String {
        val parts = ArrayList<String>()
        val g = s.glossary.trim()
        if (g.isNotEmpty()) parts.add(g.replace(Regex("\\s*\\n\\s*"), ", "))
        // The previous piece's last words, when it has already finished.
        // Pieces run in parallel, so often it has not, and that is fine.
        val prevDone = synchronized(store) { job.chunks.any { it.index == chunk.index - 1 && it.status == "done" } }
        if (prevDone) {
            val tail = store.chunkSegments(job, chunk.index - 1).joinToString(" ") { it.text }.trim().takeLast(300)
            if (tail.isNotEmpty()) parts.add(tail)
        }
        return parts.joinToString(" ").takeLast(900)
    }

    private fun runChunk(job: Job, chunk: Chunk, eng: Engine, s: QueueSettings, flag: AtomicBoolean) {
        try {
            val audio = store.chunkAudio(job, chunk)
            if (!audio.exists()) throw Fatal("The prepared audio for this part is missing.")
            val raw = eng.transcribe(audio, promptFor(job, chunk, s), s.lang) { flag.get() || !running }
            if (flag.get() || !running) {
                synchronized(store) { if (chunk.status == "working") { chunk.status = "pending"; store.save(job) } }
                return
            }
            val mapped = raw.map { Seg(chunk.toOriginal(it.startMs), chunk.toOriginal(it.endMs), it.text) }
            synchronized(store) {
                chunk.offlineTries = 0
                store.saveChunkResult(job, chunk, mapped)
            }
        } catch (e: RateLimited) {
            synchronized(store) { chunk.status = "pending"; store.save(job) }
            val until = clock() + e.waitMs
            if (until > waitUntil) waitUntil = until
            Diag.log("queue", "rate limited, wait ${e.waitMs}ms")
        } catch (e: Transient) {
            synchronized(store) {
                chunk.status = "pending"
                chunk.offlineTries++
                chunk.notBefore = clock() + minOf(60_000L, 2_000L shl minOf(chunk.offlineTries - 1, 5))
                store.save(job)
            }
            Diag.log("queue", "transient: ${e.message}")
        } catch (e: KeyRejected) {
            synchronized(store) { chunk.status = "pending"; store.save(job) }
            keyProblem = e.message
        } catch (e: Throwable) {
            synchronized(store) {
                chunk.attempts++
                if (chunk.attempts >= MAX_ATTEMPTS) {
                    chunk.status = "failed"
                    chunk.error = e.message ?: "Could not transcribe this part."
                } else {
                    chunk.status = "pending"
                    chunk.notBefore = clock() + 3_000L * chunk.attempts
                }
                store.save(job)
            }
            Diag.log("queue", "part ${chunk.index} of ${job.name}: ${e.message}")
        } finally {
            synchronized(lock) { inFlight = (inFlight - 1).coerceAtLeast(0) }
            changed()
            kick()
        }
    }

    /** Called with the store locked. */
    private fun finishJob(job: Job, s: QueueSettings) {
        val failed = job.chunks.filter { it.status == "failed" }
        closeSession(job)
        job.finishedAt = clock()
        job.note = ""
        if (failed.isEmpty()) {
            job.status = Status.DONE
            job.error = null
            store.finish(job, s.keepAudio)
        } else {
            // Keep everything that did work; Retry redoes only the failed parts.
            job.status = Status.FAILED
            job.error = "${failed.size} of ${job.chunks.size} parts failed: ${failed.first().error}"
            store.save(job)
        }
        synchronized(lock) { cancelFlags.remove(job.id) }
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        /** A wait longer than this means the hourly or daily allowance is gone. */
        const val LONG_WAIT_MS = 5 * 60 * 1000L

        fun humanWait(ms: Long): String {
            val s = (ms + 999) / 1000
            if (s < 90) return "${s}s"
            val m = (s + 30) / 60
            return if (m < 90) "$m min" else String.format("%.1f hours", m / 60.0)
        }
    }
}
