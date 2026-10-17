import app.scribe.*
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

var failures = 0
fun check(cond: Boolean, what: String) {
    if (cond) println("  ok   $what") else { println("  FAIL $what"); failures++ }
}

/** Source files hold "seconds=N" instead of audio; pieces are SCALE-compressed in time. */
class FakePreparer(val chunkSec: Int, val prepMsPerChunk: Long, val keepRatio: Double = 1.0) : Preparer {
    val prepared = ConcurrentHashMap<String, AtomicInteger>()
    override fun prepare(job: Job, store: JobStore, onChunk: (Chunk) -> Unit, cancelled: () -> Boolean) {
        val total = store.source(job).readText().substringAfter("seconds=").trim().toInt()
        job.durationMs = total * 1000L
        var startSec = (job.preparedToMs / 1000).toInt()
        var index = job.chunks.size
        while (startSec < total) {
            if (cancelled()) return
            Thread.sleep(prepMsPerChunk)
            if (cancelled()) return
            val len = minOf(chunkSec, total - startSec)
            val f = File(store.chunkDir(job.id), "c$index.m4a")
            f.parentFile.mkdirs()
            f.writeText("audioSec=${(len * keepRatio).toInt()}\nstart=$startSec")
            synchronized(store) { job.preparedToMs = (startSec + len) * 1000L }
            prepared.getOrPut(job.id) { AtomicInteger() }.incrementAndGet()
            onChunk(Chunk(index, "chunks/c$index.m4a", startSec * 1000L, (startSec + len) * 1000L, longArrayOf(0, startSec * 1000L)))
            index++
            startSec += len
        }
    }
}

/** Latency = overhead + audio seconds × factor. Optional failure injection. */
class FakeEngine(
    override val id: String,
    override val parallel: Int,
    val overheadMs: Long,
    val msPerAudioSec: Double,
    var failWith: ((Int) -> Exception?)? = null,
) : Engine {
    val calls = AtomicInteger()
    val perChunk = ConcurrentHashMap<String, AtomicInteger>()
    val active = AtomicInteger()
    var maxActive = 0
    override fun unavailable(): String? = null
    override fun transcribe(audio: File, prompt: String, lang: String, cancelled: () -> Boolean): List<Seg> {
        val n = calls.incrementAndGet()
        perChunk.getOrPut(audio.absolutePath) { AtomicInteger() }.incrementAndGet()
        failWith?.invoke(n)?.let { throw it }
        val a = active.incrementAndGet()
        synchronized(this) { if (a > maxActive) maxActive = a }
        try {
            val sec = audio.readText().substringAfter("audioSec=").substringBefore("\n").toInt()
            val start = audio.readText().substringAfter("start=").trim().toLong()
            val until = System.currentTimeMillis() + overheadMs + (sec * msPerAudioSec).toLong()
            while (System.currentTimeMillis() < until) {
                if (cancelled()) return emptyList()
                Thread.sleep(5)
            }
            return listOf(Seg(0, sec * 1000L, "words from ${audio.parentFile.parentFile.name.take(6)} at $start"))
        } finally { active.decrementAndGet() }
    }
}

fun newStore(dir: File) = JobStore(dir).also { it.load() }

fun addJob(store: JobStore, name: String, seconds: Int): Job {
    val job = Job(java.util.UUID.randomUUID().toString(), System.currentTimeMillis())
    job.name = name
    store.add(job)
    store.source(job).writeText("seconds=$seconds")
    synchronized(store) { job.copied = true; job.size = 1; job.hash = name; store.save(job) }
    return job
}

class Watch(val store: JobStore) : JobRunner.Listener {
    val finishedAt = ConcurrentHashMap<String, Long>()
    val firstWorkAt = ConcurrentHashMap<String, Long>()
    @Volatile var idle = false
    override fun onChange() {
        val now = System.currentTimeMillis()
        for (j in store.all()) if (j.status == Status.PROCESSING) firstWorkAt.putIfAbsent(j.id, now)
    }
    override fun onFinished(job: Job) { finishedAt[job.id] = System.currentTimeMillis() }
    override fun onIdle() { idle = true }
}

fun waitIdle(w: Watch, timeoutMs: Long): Boolean {
    val until = System.currentTimeMillis() + timeoutMs
    while (!w.idle && System.currentTimeMillis() < until) Thread.sleep(10)
    return w.idle
}

fun tmpDir(name: String) = File(System.getProperty("java.io.tmpdir"), "scribe-sim-$name-${System.nanoTime()}").apply { mkdirs() }

/* ───────────────────────────── tests ───────────────────────────── */

fun testLiveResults() {
    println("\n[1] five files at once: the first is readable before the last starts")
    val dir = tmpDir("live")
    val store = newStore(dir)
    val jobs = (1..5).map { addJob(store, "file$it.m4a", 1800) }   // 30 min each, 6 pieces
    val eng = FakeEngine("groq", 6, 30, 0.2)
    val runner = JobRunner(store, FakePreparer(300, 20), mapOf("groq" to eng), { QueueSettings(filesAtOnce = 2, uploadsAtOnce = 3) })
    val w = Watch(store); runner.listener = w
    runner.start()
    check(waitIdle(w, 30_000), "batch finished")
    val first = w.finishedAt[jobs[0].id] ?: Long.MAX_VALUE
    val lastStart = w.firstWorkAt[jobs[4].id] ?: 0
    check(first < lastStart, "file 1 done (${first % 100000}) before file 5 started (${lastStart % 100000})")
    val order = jobs.map { w.finishedAt[it.id] ?: Long.MAX_VALUE }
    check(order == order.sorted(), "files finished in the order they were shared")
    check(store.all().all { it.status == Status.DONE }, "all five done")
    check(eng.maxActive <= 3, "never more than 3 uploads at once (saw ${eng.maxActive})")
    val segs = store.segments(jobs[2])
    check(segs.size == 6 && segs.map { it.startMs } == (0 until 6).map { it * 300_000L }, "result in order with original times")
}

fun testKillAndResume() {
    println("\n[2] kill mid-batch, relaunch: finished kept, unfinished resume, nothing redone")
    val dir = tmpDir("kill")
    var store = newStore(dir)
    val jobs = (1..5).map { addJob(store, "f$it.m4a", 1500) }
    val eng = FakeEngine("groq", 6, 40, 0.2)
    val prep = FakePreparer(300, 15)
    var runner = JobRunner(store, prep, mapOf("groq" to eng), { QueueSettings(filesAtOnce = 2, uploadsAtOnce = 3) })
    var w = Watch(store); runner.listener = w
    runner.start()
    // Let roughly the first file finish, then "kill" the process.
    val until = System.currentTimeMillis() + 10_000
    while (w.finishedAt.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(5)
    Thread.sleep(60)
    runner.stop()
    Thread.sleep(150)   // in-flight threads notice and give up
    val doneBefore = store.all().filter { it.status == Status.DONE }.map { it.id }.toSet()
    val chunksDoneBefore = store.all().sumOf { it.chunksDone() }
    check(doneBefore.isNotEmpty(), "${doneBefore.size} file(s) finished before the kill")
    check(store.all().any { !Status.finished(it.status) }, "some unfinished at kill time")

    // A brand new process: nothing carried over except the files on disk.
    store = newStore(dir)
    check(store.all().size == 5, "all five jobs restored from disk")
    check(store.all().none { it.status == Status.PROCESSING }, "interrupted jobs put back to waiting")
    check(store.all().flatMap { it.chunks }.none { it.status == "working" }, "interrupted pieces put back to pending")
    check(store.all().filter { it.status == Status.DONE }.map { it.id }.toSet() == doneBefore, "finished results still there")
    check(store.all().sumOf { it.chunksDone() } >= chunksDoneBefore, "saved pieces kept ($chunksDoneBefore)")
    val callsBefore = eng.calls.get()
    eng.perChunk.clear()
    runner = JobRunner(store, prep, mapOf("groq" to eng), { QueueSettings(filesAtOnce = 2, uploadsAtOnce = 3) })
    w = Watch(store); runner.listener = w
    runner.start()
    check(waitIdle(w, 30_000), "resumed batch finished")
    check(store.all().all { it.status == Status.DONE }, "every file done after resume")
    val redone = eng.perChunk.values.count { it.get() > 1 }
    check(redone == 0, "no piece transcribed twice after resume")
    val totalChunks = store.all().sumOf { it.chunks.size }
    check(callsBefore + eng.calls.get() - callsBefore <= totalChunks + 6, "total engine calls ${eng.calls.get()} for $totalChunks pieces")
}

fun testKillMidPreparation() {
    println("\n[3] kill halfway through cutting a long file: resumes after the last saved piece")
    val dir = tmpDir("prep")
    var store = newStore(dir)
    val job = addJob(store, "long.m4a", 4 * 3600)   // four hours, 48 pieces
    val prep = FakePreparer(300, 25)
    val eng = FakeEngine("groq", 6, 5, 0.01)
    var runner = JobRunner(store, prep, mapOf("groq" to eng), { QueueSettings() })
    var w = Watch(store); runner.listener = w
    runner.start()
    Thread.sleep(400)
    runner.stop()
    Thread.sleep(100)
    store = newStore(dir)
    val j = store.get(job.id)!!
    val madeBefore = j.chunks.size
    val doneBefore = j.chunksDone()
    check(madeBefore in 2..47, "$madeBefore of 48 pieces prepared before the kill")
    check(j.preparedToMs == madeBefore * 300_000L, "resume point saved (${j.preparedToMs / 60000} min)")
    val prep2 = FakePreparer(300, 25)
    runner = JobRunner(store, prep2, mapOf("groq" to eng), { QueueSettings() })
    w = Watch(store); runner.listener = w
    eng.perChunk.clear()
    runner.start()
    check(waitIdle(w, 30_000), "finished after resume")
    val k = store.get(job.id)!!
    check(k.status == Status.DONE && k.chunks.size == 48, "48 pieces, done")
    check((prep2.prepared[job.id]?.get() ?: 0) == 48 - madeBefore, "only the remaining ${48 - madeBefore} pieces were prepared again")
    check(eng.perChunk.values.none { it.get() > 1 }, "no piece transcribed twice")
    check(store.segments(k).map { it.startMs } == (0 until 48).map { it * 300_000L }, "transcript continuous, in order ($doneBefore were already done)")
}

fun testRateLimitAndNetwork() {
    println("\n[4] rate limits and a dropped connection: jobs wait and retry, nothing lost")
    val dir = tmpDir("rl")
    val store = newStore(dir)
    val jobs = (1..3).map { addJob(store, "r$it.m4a", 900) }
    val eng = FakeEngine("groq", 6, 10, 0.05)
    eng.failWith = { n ->
        when (n) {
            in 2..3 -> RateLimited(600, "429")
            in 6..11 -> Transient("network down")
            else -> null
        }
    }
    val runner = JobRunner(store, FakePreparer(300, 5), mapOf("groq" to eng), { QueueSettings(uploadsAtOnce = 3) })
    val w = Watch(store); runner.listener = w
    val notes = HashSet<String>()
    val watcher = Thread {
        while (!w.idle) { store.all().forEach { if (it.note.isNotEmpty()) synchronized(notes) { notes.add(it.note.substringBefore(" —")) } }; Thread.sleep(20) }
    }.apply { start() }
    val t0 = System.currentTimeMillis()
    runner.start()
    check(waitIdle(w, 60_000), "finished despite failures (${System.currentTimeMillis() - t0} ms)")
    watcher.join()
    check(jobs.all { store.get(it.id)!!.status == Status.DONE }, "all three done, none failed")
    check(store.all().flatMap { it.chunks }.all { it.attempts == 0 }, "waits did not count as failed attempts")
    println("       notes seen: $notes")
    check(notes.any { it.startsWith("Waiting for the rate limit") }, "showed 'waiting for the rate limit'")
}

fun testFallbackAndBlocked() {
    println("\n[5] engines: no key blocks with a reason; daily allowance gone falls back to on-device")
    val dir = tmpDir("fb")
    val store = newStore(dir)
    addJob(store, "a.m4a", 600)
    val groq = object : Engine by FakeEngine("groq", 6, 5, 0.01) {
        override val id = "groq"
        override fun unavailable() = "Add a free Groq key in Settings"
    }
    val runner = JobRunner(store, FakePreparer(300, 5), mapOf("groq" to groq), { QueueSettings(engine = "auto") })
    val w = Watch(store); runner.listener = w
    runner.start()
    Thread.sleep(300)
    check(store.all().first().note.contains("Groq key"), "waiting with reason: '${store.all().first().note}'")
    check(store.all().first().status != Status.FAILED, "not failed while blocked")
    runner.stop()

    val dir2 = tmpDir("fb2")
    val store2 = newStore(dir2)
    addJob(store2, "b.m4a", 1200)
    val groq2 = FakeEngine("groq", 6, 5, 0.01)
    groq2.failWith = { n -> if (n >= 2) RateLimited(30 * 60 * 1000L, "daily") else null }
    val local = FakeEngine("local", 1, 5, 0.02)
    val r2 = JobRunner(store2, FakePreparer(300, 5), mapOf("groq" to groq2, "local" to local), { QueueSettings(engine = "auto") })
    val w2 = Watch(store2); r2.listener = w2
    r2.start()
    check(waitIdle(w2, 20_000), "finished using the fallback")
    val j = store2.all().first()
    check(j.status == Status.DONE && local.calls.get() >= 2, "on-device engine took over (${local.calls.get()} pieces), engines '${j.engines()}'")
}

fun testCancelRetryReorder() {
    println("\n[6] cancel, retry, reorder")
    val dir = tmpDir("crr")
    val store = newStore(dir)
    val a = addJob(store, "a.m4a", 1500); val b = addJob(store, "b.m4a", 600); val c = addJob(store, "c.m4a", 600)
    store.move(c.id, -2)
    check(store.all().map { it.name } == listOf("c.m4a", "a.m4a", "b.m4a"), "moved c to the front")
    val eng = FakeEngine("groq", 6, 60, 0.1)
    val runner = JobRunner(store, FakePreparer(300, 5), mapOf("groq" to eng), { QueueSettings(filesAtOnce = 1, uploadsAtOnce = 2) })
    val w = Watch(store); runner.listener = w
    runner.start()
    Thread.sleep(80)
    runner.cancel(b.id)
    check(store.get(b.id)!!.status == Status.CANCELLED, "b cancelled while waiting")
    check(waitIdle(w, 20_000), "rest finished")
    check(w.finishedAt[c.id]!! < w.finishedAt[a.id]!!, "c (moved up) finished before a")
    check(store.get(b.id)!!.chunks.isEmpty(), "cancelled b never started")
    val w2 = Watch(store); runner.listener = w2
    runner.retry(b.id)
    runner.start()
    check(waitIdle(w2, 20_000) && store.get(b.id)!!.status == Status.DONE, "b done after retry")
}

fun testDuplicates() {
    println("\n[7] duplicate detection by content hash")
    val store = newStore(tmpDir("dup"))
    val a = addJob(store, "same", 60)
    check(store.findByHash("same", "other") == a, "same fingerprint found")
    check(store.findByHash("same", a.id) == null, "a job is not its own duplicate")
    check(store.findByHash("", "x") == null, "an unfingerprinted job matches nothing")
}

fun testAtomicWrites() {
    println("\n[8] atomic writes")
    val dir = tmpDir("atomic")
    val f = File(dir, "x.json")
    JobStore.writeAtomic(f, "{\"v\":1}")
    JobStore.writeAtomic(f, "{\"v\":2}")
    check(JobStore.readJson(f)?.getInt("v") == 2, "replaced in place")
    check(dir.listFiles()!!.none { it.name.endsWith(".tmp") }, "no temp file left behind")
    // A torn temp file from a crash is ignored; the real file still reads.
    File(dir, "x.json.tmp").writeText("{\"v\":")
    check(JobStore.readJson(f)?.getInt("v") == 2, "half-written temp file does not affect the saved one")
    val store = JobStore(File(dir, "jobs")); val j = addJob(store, "z", 10)
    File(store.dir(j.id), "job.json.tmp").writeText("garbage")
    check(newStore(File(dir, "jobs")).get(j.id) != null, "job loads despite a stray temp file")
}

/* ── audio processing ── */

fun testResampler() {
    println("\n[9] resampler: 48 kHz → 16 kHz keeps speech, blocks aliasing")
    fun tone(freq: Double, rate: Int, sec: Double) = FloatArray((rate * sec).toInt()) { (0.5 * sin(2 * PI * freq * it / rate)).toFloat() }
    fun run(input: FloatArray, inRate: Int): FloatArray {
        val r = Resampler(inRate, 16000); val out = ArrayList<Float>()
        var i = 0
        while (i < input.size) { val n = minOf(1000, input.size - i); r.push(input.copyOfRange(i, i + n), n) { o, m -> for (k in 0 until m) out.add(o[k]) }; i += n }
        return out.toFloatArray()
    }
    fun rms(a: FloatArray) = kotlin.math.sqrt(a.drop(1000).map { it * it }.average())
    val pass = run(tone(1000.0, 48000, 2.0), 48000)
    check(abs(pass.size - 32000) < 40, "length right (${pass.size} ≈ 32000)")
    check(abs(rms(pass) - 0.3536) < 0.02, "1 kHz passes at full level (rms ${"%.3f".format(rms(pass))})")
    val alias = run(tone(12000.0, 48000, 2.0), 48000)
    check(rms(alias) < 0.01, "12 kHz removed rather than folded to 4 kHz (rms ${"%.4f".format(rms(alias))})")
    val up = run(tone(1000.0, 8000, 1.0), 8000)
    check(abs(up.size - 16000) < 40 && abs(rms(up) - 0.3536) < 0.03, "8 kHz → 16 kHz works too")
    // Zero crossings confirm the frequency survived.
    val zc = (1 until pass.size).count { pass[it - 1] < 0 && pass[it] >= 0 }
    check(abs(zc - 2000) < 5, "still 1 kHz ($zc crossings in 2 s)")
}

fun testChunker() {
    println("\n[10] silence trimming and cutting")
    val rate = 16000
    // 20 minutes: speech bursts of 3–8 s, pauses of 0.2–6 s, quiet room noise.
    val rnd = Random(7)
    val samples = ArrayList<Float>()
    val speechAt = ArrayList<Pair<Long, Long>>()
    var t = 0
    while (t < rate * 1200) {
        val speak = rate * (3 + rnd.nextInt(6))
        speechAt.add(t * 1000L / rate to (t + speak) * 1000L / rate)
        for (i in 0 until speak) samples.add((0.2 * sin(2 * PI * 220 * i / rate) * (0.6 + 0.4 * sin(2 * PI * 3 * i / rate)) + rnd.nextDouble(-0.002, 0.002)).toFloat())
        t += speak
        val pause = (rate * (0.2 + rnd.nextDouble() * 5.8)).toInt()
        for (i in 0 until pause) samples.add(rnd.nextDouble(-0.002, 0.002).toFloat())
        t += pause
    }
    val pcm = samples.toFloatArray()
    val chunks = ArrayList<Triple<Int, Long, LongArray>>()
    val starts = ArrayList<Long>()
    val ch = Chunker(0, true, 300_000, 360_000) { _, len, s, _, map -> chunks.add(Triple(len, s, map)); starts.add(s) }
    var i = 0
    while (i < pcm.size) { val n = minOf(4096, pcm.size - i); ch.push(pcm.copyOfRange(i, i + n), n); i += n }
    ch.finish()
    val totalSec = pcm.size / rate
    val keptSec = chunks.sumOf { it.first } / rate
    println("       ${chunks.size} pieces, ${totalSec}s in, ${keptSec}s kept (${100 - keptSec * 100 / totalSec}% removed)")
    check(keptSec < totalSec * 0.85, "long pauses shortened")
    val speechSec = speechAt.sumOf { it.second - it.first } / 1000
    check(keptSec >= speechSec, "no speech removed (${speechSec}s of speech, ${keptSec}s kept)")
    check(chunks.all { it.first <= 360 * rate }, "no piece over 6 minutes")
    check(chunks.dropLast(1).all { it.first >= 290 * rate }, "pieces about 5 minutes")
    // Every speech burst's start must map back to its true time.
    var worst = 0L
    for ((len, start, map) in chunks) {
        val c = Chunk(0, "", start, 0, map)
        // Walk the piece, recompute where each kept sample came from, and compare against the source.
        for (p in 0 until map.size / 2) {
            val pieceMs = map[p * 2]; val orig = c.toOriginal(pieceMs)
            if (orig != map[p * 2 + 1]) worst = maxOf(worst, abs(orig - map[p * 2 + 1]))
        }
    }
    check(worst == 0L, "map is self-consistent")
    // Content check: rebuild original time for each kept sample and compare samples.
    var mismatches = 0
    var checked = 0
    val rebuilt = ArrayList<FloatArray>()
    val ch2 = Chunker(0, true, 300_000, 360_000) { buf, len, s, _, map ->
        val c = Chunk(0, "", s, 0, map)
        var k = 0
        while (k < len) {
            val origMs = c.toOriginal(k * 1000L / rate)
            val origIdx = (origMs * rate / 1000).toInt()
            if (origIdx in pcm.indices) {
                val expected = (pcm[origIdx].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                if (abs(expected - buf[k]) > 2) mismatches++
                checked++
            }
            k += 976   // a whole number of milliseconds, so the map lands on exact samples
        }
    }
    i = 0
    while (i < pcm.size) { val n = minOf(4096, pcm.size - i); ch2.push(pcm.copyOfRange(i, i + n), n); i += n }
    ch2.finish()
    check(mismatches * 100 < checked, "kept audio lines up with the original timeline ($mismatches/$checked off)")

    val plain = ArrayList<Int>()
    val ch3 = Chunker(0, false) { _, len, _, _, _ -> plain.add(len) }
    i = 0
    while (i < pcm.size) { val n = minOf(4096, pcm.size - i); ch3.push(pcm.copyOfRange(i, i + n), n); i += n }
    ch3.finish()
    check(abs(plain.sum() - pcm.size) < rate, "trimming off keeps everything")
}

/* ── Groq client against a local server ── */

fun testGroq() {
    println("\n[11] Groq client: replies, 429, 401, 5xx, offline")
    val responses = ArrayDeque<Triple<Int, String, Map<String, String>>>()
    var lastBody = ""
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { ex ->
        lastBody = ex.requestBody.readBytes().toString(Charsets.ISO_8859_1)
        val (code, body, headers) = synchronized(responses) { responses.removeFirst() }
        headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
        val b = body.toByteArray()
        ex.sendResponseHeaders(code, b.size.toLong()); ex.responseBody.use { it.write(b) }
    }
    server.start()
    val base = "http://127.0.0.1:${server.address.port}/"
    val audio = File(tmpDir("groq"), "c0.m4a").apply { writeBytes(ByteArray(5000) { it.toByte() }) }
    val g = GroqEngine({ "gsk_test" }, base, 0)
    responses.add(Triple(200, """{"text":"x","segments":[{"start":0.0,"end":2.5,"text":" Ciao a tutti.","no_speech_prob":0.01,"avg_logprob":-0.2},{"start":2.5,"end":30.0,"text":" Sottotitoli a cura di QTSS","no_speech_prob":0.9,"avg_logprob":-1.4},{"start":30,"end":33,"text":" Oggi parliamo di Dante.","no_speech_prob":0.1,"avg_logprob":-0.3}]}""", emptyMap()))
    val segs = g.transcribe(audio, "Dante, Beatrice", "it") { false }
    check(segs.map { it.text } == listOf("Ciao a tutti.", "Oggi parliamo di Dante."), "segments parsed, silence hallucination dropped")
    check(segs[1].startMs == 30_000L && segs[1].endMs == 33_000L, "times in ms")
    check(lastBody.contains("whisper-large-v3-turbo") && lastBody.contains("verbose_json") && lastBody.contains("name=\"language\"") && lastBody.contains("Dante, Beatrice"), "request carries model, format, language, prompt")
    check(lastBody.contains("filename=\"audio.m4a\""), "file sent with its extension")

    responses.add(Triple(429, """{"error":{"message":"Rate limit reached for model `whisper-large-v3-turbo` on seconds of audio per hour (ASH): Limit 7200, Used 7190. Please try again in 1m2.5s."}}""", emptyMap()))
    val rl = runCatching { g.transcribe(audio, "", "") { false } }.exceptionOrNull()
    check(rl is RateLimited && (rl as RateLimited).waitMs == 63_250L, "429 → wait ${(rl as? RateLimited)?.waitMs} ms (62.5 s + margin)")
    responses.add(Triple(429, "{}", mapOf("retry-after" to "7")))
    val rl2 = runCatching { g.transcribe(audio, "", "") { false } }.exceptionOrNull()
    check(rl2 is RateLimited && (rl2 as RateLimited).waitMs == 7_750L, "retry-after header honoured")
    responses.add(Triple(401, """{"error":{"message":"Invalid API Key"}}""", emptyMap()))
    check(runCatching { g.transcribe(audio, "", "") { false } }.exceptionOrNull() is KeyRejected, "401 → key rejected")
    responses.add(Triple(503, "busy", emptyMap()))
    check(runCatching { g.transcribe(audio, "", "") { false } }.exceptionOrNull() is Transient, "503 → retry later")
    responses.add(Triple(400, """{"error":{"message":"could not process file"}}""", emptyMap()))
    val bad = runCatching { g.transcribe(audio, "", "") { false } }.exceptionOrNull()
    check(bad is Fatal && bad.message!!.contains("could not process file"), "400 → this part fails, with Groq's reason")
    server.stop(0)
    val off = runCatching { GroqEngine({ "k" }, base, 0).transcribe(audio, "", "") { false } }.exceptionOrNull()
    check(off is Transient, "server gone → retry later, not failed")
    check(GroqEngine({ "" }).unavailable() != null, "no key → unavailable, with a reason")
    check(GroqEngine.retryDelayFrom("Please try again in 2h3m4s") == (2 * 3600 + 3 * 60 + 4) * 1000L, "parses hours too")

    val paced = GroqEngine({ "k" }, base, 300)
    val t0 = System.currentTimeMillis()
    repeat(3) { runCatching { paced.transcribe(audio, "", "") { false } } }
    check(System.currentTimeMillis() - t0 >= 600, "request starts are spaced to stay under the per-minute limit")
}

/* ── before/after timing ── */

/** Groq's free plan: at most 7,200 seconds of audio in any hour. Time here is real seconds. */
class Allowance(val perHour: Double = 7200.0) {
    private val used = ArrayList<Pair<Double, Double>>()   // (start time, audio seconds)
    /** @return 0 if the request fits now, else how long to wait. */
    @Synchronized fun take(now: Double, sec: Double): Double {
        used.removeAll { it.first <= now - 3600 }
        if (used.sumOf { it.second } + sec <= perHour) { used.add(now to sec); return 0.0 }
        var acc = used.sumOf { it.second } + sec - perHour
        for ((t, s) in used.sortedBy { it.first }) { acc -= s; if (acc <= 0) return t + 3600 - now + 0.5 }
        return 3600.0
    }
}

/**
 * Same files, same simulated network, same Groq speed and the same free-plan
 * allowance; only the strategy differs.
 *
 * Before: one file at a time; a file under 24 MB went up whole, a bigger one
 * as ~22 MB pieces one after another, at the recorder's own bitrate; when the
 * allowance ran out the batch stopped and had to be shared again (here it is
 * generously assumed to be re-shared the instant the allowance returns).
 * After: the real scheduler — two files at once, 5-minute pieces, three in
 * flight, 16 kHz mono AAC at 32 kbps, long pauses shortened (assumed 20%).
 */
fun benchmark() {
    println("\n[12] before/after timing (simulated; 1 s of test = 1 min real)")
    val upKbps = 2000.0          // a decent mobile uplink
    val srcKbps = 128.0          // phone recorders commonly save AAC at 128 kbps
    val groqSpeed = 200.0        // audio seconds transcribed per second, after upload
    val overheadSec = 1.0
    val scale = 1000.0 / 60.0    // ms of test per real second
    fun latency(audioSec: Double, kbps: Double) = overheadSec + audioSec * kbps / upKbps + audioSec / groqSpeed

    data class Set(val name: String, val minutes: List<Int>)
    for (set in listOf(Set("5 short voice notes", listOf(4, 7, 3, 10, 6)), Set("5 lectures", listOf(50, 90, 45, 60, 75)))) {
        // Before, on a simulated clock.
        val partSec = 22.0 * 1024 * 1024 * 8 / (srcKbps * 1000)
        val allowB = Allowance()
        var t = 0.0
        var firstBefore = 0.0
        for ((i, m) in set.minutes.withIndex()) {
            var left = m * 60.0
            while (left > 0) {
                val p = minOf(left, partSec)
                t += allowB.take(t, p)
                allowB.take(t, 0.0)
                t += latency(p, srcKbps)
                left -= p
            }
            if (i == 0) firstBefore = t
        }
        val before = t

        // After, through the real scheduler, in scaled real time.
        val dir = tmpDir("bench")
        val store = newStore(dir)
        set.minutes.forEachIndexed { i, m -> addJob(store, "f$i", m * 60) }
        val allowA = Allowance()
        val t0 = System.currentTimeMillis()
        val nowReal = { (System.currentTimeMillis() - t0) / scale }
        val eng = object : Engine {
            override val id = "groq"; override val parallel = 6
            override fun unavailable(): String? = null
            override fun transcribe(audio: File, prompt: String, lang: String, cancelled: () -> Boolean): List<Seg> {
                val sec = audio.readText().substringAfter("audioSec=").substringBefore("\n").toDouble()
                val wait = allowA.take(nowReal(), sec)
                if (wait > 0) throw RateLimited((wait * scale).toLong().coerceAtLeast(1), "ASH")
                Thread.sleep((latency(sec, 32.0) * scale).toLong())
                return listOf(Seg(0, 1, "x"))
            }
        }
        // Preparing (decode + encode) runs at roughly 60× real time on a phone.
        val prep = FakePreparer(300, (300 / 60.0 * scale).toLong(), keepRatio = 0.8)
        val runner = JobRunner(store, prep, mapOf("groq" to eng), { QueueSettings(filesAtOnce = 2, uploadsAtOnce = 3) }, clock = System::currentTimeMillis)
        val w = Watch(store); runner.listener = w
        runner.start()
        waitIdle(w, 400_000)
        val after = nowReal()
        val firstAfter = ((w.finishedAt[store.all().first().id] ?: 0L) - t0) / scale
        val fm = { sec: Double -> if (sec < 600) "%.1f min".format(sec / 60) else "%.0f min".format(sec / 60) }
        println("       ${set.name} (${set.minutes.sum()} min of audio):")
        println("         whole batch    before ${fm(before)}   after ${fm(after)}   (${"%.1f".format(before / after)}x)")
        println("         first result   before ${fm(firstBefore)}   after ${fm(firstAfter)}   (${"%.1f".format(firstBefore / firstAfter)}x)")
        check(store.all().all { it.status == Status.DONE }, "all done, rate limits waited out automatically")
    }
}

fun main(args: Array<String>) {
    val only = args.firstOrNull()
    val tests = listOf(::testLiveResults, ::testKillAndResume, ::testKillMidPreparation, ::testRateLimitAndNetwork,
        ::testFallbackAndBlocked, ::testCancelRetryReorder, ::testDuplicates, ::testAtomicWrites,
        ::testResampler, ::testChunker, ::testGroq, ::benchmark)
    for (t in tests) if (only == null || t.name.contains(only, ignoreCase = true)) t()
    println(if (failures == 0) "\nALL PASSED" else "\n$failures FAILED")
    if (failures > 0) System.exit(1)
    System.exit(0)
}
