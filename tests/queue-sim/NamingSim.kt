import app.scribe.*
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.util.Calendar
import java.util.concurrent.atomic.AtomicInteger

fun testNamingHelpers() {
    println("\n[13] naming: subjects, numbers, file names, recording dates")
    check(Naming.fold(" Storia  ") == Naming.fold("storia") && Naming.fold("Perché") == "perche", "subjects compare without case, spaces or accents")
    check(Naming.matchSubject("storia", listOf("Filosofia", "Storia")) == "Storia", "a known subject keeps its spelling")
    check(Naming.matchSubject("Chimica", listOf("Storia")) == "Chimica", "a new subject is kept as proposed")
    check(Naming.format(Naming.DEFAULT_FORMAT, "Storia", 3, 2, "La rivoluzione francese") == "Storia 03 - La rivoluzione francese", "default format, zero-padded")
    check(Naming.format("{subject}({n})({theme})", "Storia", 12, 2, "Napoleone") == "Storia(12)(Napoleone)", "custom format")
    check(Naming.fileSafe("Diritto: art. 3/4 \"uguaglianza\"?") == "Diritto art. 3 4 uguaglianza", "characters a file name cannot hold are removed")
    fun at(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0, s: Int = 0) =
        Calendar.getInstance().apply { clear(); set(y, mo - 1, d, h, mi, s) }.timeInMillis
    check(Naming.dateFromName("Registrazione 2026-10-03 10-15-22.m4a") == at(2026, 10, 3, 10, 15, 22), "Xiaomi-style name")
    check(Naming.dateFromName("20261003_101522.m4a") == at(2026, 10, 3, 10, 15, 22), "compact name")
    check(Naming.dateFromName("Rec 2026.10.03 10.15.m4a") == at(2026, 10, 3, 10, 15), "dotted name")
    check(Naming.dateFromName("Voice 001.m4a") == null, "no date, no guess")
    val long = "a".repeat(20_000)
    check(Naming.excerpt(long).length < 6100, "long transcripts are sampled, not sent whole")

    // Numbering follows recording order, not the order they finished in.
    fun job(id: String, subject: String, theme: String, at: Long) = Job(id, 0).apply {
        naming = "done"; this.subject = subject; this.theme = theme; recordedAt = at; status = Status.DONE
    }
    val a = job("a", "Storia", "Napoleone", 300)
    val b = job("b", "storia", "La rivoluzione francese", 100)
    val c = job("c", "Filosofia", "Kant", 200)
    val d = job("d", "Storia", "Il Congresso di Vienna", 400)
    Naming.renumber(listOf(a, b, c, d), Naming.DEFAULT_FORMAT)
    check(listOf(b, a, d).map { it.title } == listOf("Storia 01 - La rivoluzione francese", "Storia 02 - Napoleone", "Storia 03 - Il Congresso di Vienna"),
        "Storia numbered by date: ${listOf(b, a, d).map { it.title }}")
    check(c.title == "Filosofia 01 - Kant", "each subject counts from 1")
    check(b.subject == a.subject, "one spelling per subject")
    // A recording older than the rest is named later: everything after it moves up one.
    val e = job("e", "Storia", "L'Illuminismo", 50)
    val changed = Naming.renumber(listOf(a, b, c, d, e), Naming.DEFAULT_FORMAT)
    check(e.title.startsWith("Storia 01") && d.title.startsWith("Storia 04") && changed.size == 4,
        "an earlier recording slots in and the rest renumber (${changed.size} changed)")
}

fun testClassifier() {
    println("\n[14] naming model: request, answer, retired models, limits")
    val replies = ArrayDeque<Pair<Int, String>>()
    val requests = ArrayList<JSONObject>()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { ex ->
        requests.add(JSONObject(ex.requestBody.readBytes().toString(Charsets.UTF_8)))
        val (code, body) = synchronized(replies) { replies.removeFirst() }
        val b = body.toByteArray()
        ex.sendResponseHeaders(code, b.size.toLong()); ex.responseBody.use { it.write(b) }
    }
    server.start()
    val base = "http://127.0.0.1:${server.address.port}/"
    fun answer(subject: String, theme: String) = 200 to JSONObject().put("choices", org.json.JSONArray().put(
        JSONObject().put("message", JSONObject().put("content", "Here: {\"subject\": \"$subject\", \"theme\": \"$theme.\"}")))).toString()
    val text = "Oggi parliamo della rivoluzione francese, delle sue cause e della presa della Bastiglia. " .repeat(30)
    val cl = Classifier({ "gsk_x" }, base)

    replies.add(answer("storia", "«Le cause della rivoluzione francese»"))
    val (s1, t1) = cl.classify(text, emptyList(), listOf("Storia", "Filosofia"), "it")
    check(s1 == "Storia" && t1 == "Le cause della rivoluzione francese", "answer cleaned and matched: $s1 / $t1")
    val req = requests.last()
    val sys = req.getJSONArray("messages").getJSONObject(0).getString("content")
    check(sys.contains("Storia, Filosofia") && req.getString("model") == "openai/gpt-oss-120b" && req.optString("reasoning_effort") == "low",
        "known subjects offered, best model, low reasoning effort")
    check(req.getJSONArray("messages").getJSONObject(1).getString("content").length < 6200, "only an excerpt is sent")

    replies.add(answer("Chimica", "Legami covalenti"))
    val (s2, _) = cl.classify(text, listOf("Storia", "Filosofia", "Altro"), emptyList(), "it")
    check(s2 == "Altro", "with the user's list, an off-list subject becomes Altro (got $s2)")
    check(requests.last().getJSONArray("messages").getJSONObject(0).getString("content").contains("MUST be exactly one of: Storia, Filosofia, Altro"), "the user's list is binding")

    replies.add(404 to "{\"error\":{\"message\":\"The model does not exist\",\"code\":\"model_not_found\"}}")
    replies.add(answer("Storia", "Napoleone"))
    val (s3, t3) = cl.classify(text, emptyList(), emptyList(), "it")
    check(s3 == "Storia" && t3 == "Napoleone" && requests.last().getString("model") == "openai/gpt-oss-20b", "a retired model steps down the list")

    replies.add(429 to "{\"error\":{\"message\":\"Rate limit reached for model on tokens per minute (TPM). Please try again in 7.5s.\"}}")
    check(runCatching { cl.classify(text, emptyList(), emptyList(), "it") }.exceptionOrNull().let { it is RateLimited && it.waitMs == 8250L }, "429 → wait")
    replies.add(401 to "{}")
    check(runCatching { cl.classify(text, emptyList(), emptyList(), "it") }.exceptionOrNull() is KeyRejected, "401 → key problem")
    check(Classifier({ "" }).unavailable() != null, "no key → says why")
    val (s4, t4) = cl.classify("ok", emptyList(), emptyList(), "it")
    check(s4 == "Altro" && t4 == "Registrazione breve", "nearly empty recording is not sent at all")
    server.stop(0)
}

/** Names jobs by a fixed table and renumbers, as the app's organiser does. */
class FakeNamer(val store: JobStore, val table: Map<String, Pair<String, String>>) : PostProcessor {
    val calls = AtomicInteger()
    val namedAt = HashMap<String, Long>()
    var failFirst = 0
    override fun enabled() = true
    override fun unavailable(): String? = null
    override fun process(job: Job) {
        if (calls.incrementAndGet() <= failFirst) throw RateLimited(300, "429")
        val (subject, theme) = table.getValue(job.name)
        synchronized(store) {
            job.subject = subject; job.theme = theme; job.naming = "done"
            Naming.renumber(store.all(), Naming.DEFAULT_FORMAT)
            store.all().forEach { store.save(it) }
            namedAt[job.id] = System.currentTimeMillis()
        }
    }
}

fun testLibraryAndNaming() {
    println("\n[15] the phone's recordings: read when their turn comes, named as each finishes")
    val dir = tmpDir("lib")
    var store = newStore(dir)
    // Four recordings found on the phone, oldest first, plus one that is the
    // same file as something shared earlier.
    val shared = addJob(store, "condivisa.m4a", 600)
    synchronized(store) { shared.hash = "same-content"; shared.status = Status.DONE; shared.naming = "pending"; store.save(shared) }
    val names = listOf("Lezione storia 1.m4a", "Lezione filo 1.m4a", "Lezione storia 2.m4a", "Copia condivisa.m4a", "Lezione storia 3.m4a")
    val jobs = names.mapIndexed { i, n ->
        Job("lib$i", System.currentTimeMillis()).apply { name = n; mediaUri = "content://media/external/audio/media/$i"; recordedAt = 1000L * (i + 1) }
            .also { store.add(it) }
    }
    val table = mapOf(
        "condivisa.m4a" to ("Filosofia" to "Platone"),
        "Lezione storia 1.m4a" to ("Storia" to "La rivoluzione francese"),
        "Lezione filo 1.m4a" to ("Filosofia" to "Kant"),
        "Lezione storia 2.m4a" to ("Storia" to "Napoleone"),
        "Lezione storia 3.m4a" to ("Storia" to "Il Congresso di Vienna"),
    )
    val materialized = ArrayList<String>()
    val materializer = { job: Job ->
        materialized.add(job.name)
        if (job.name.startsWith("Copia")) {
            synchronized(store) {
                // As the app does: the shared copy takes the library file's place and date.
                shared.mediaUri = job.mediaUri
                shared.recordedAt = job.recordedAt
                store.save(shared)
                store.remove(job.id)
            }
            false
        } else {
            store.source(job).writeText("seconds=900")
            synchronized(store) { job.copied = true; store.save(job) }
            true
        }
    }
    val namer = FakeNamer(store, table).apply { failFirst = 1 }
    val eng = FakeEngine("groq", 6, 60, 0.2)
    var runner = JobRunner(store, FakePreparer(300, 10), mapOf("groq" to eng), { QueueSettings(filesAtOnce = 2) },
        materializer = materializer, namer = namer)
    var w = Watch(store); runner.listener = w
    runner.start()
    check(waitIdle(w, 30_000), "all done and named")
    check(materialized.size == 5, "each library file read once, when its turn came")
    check(store.get("lib3") == null && shared.mediaUri.endsWith("/3"), "the copy of a shared file was linked to it, not transcribed again")
    val firstNamed = namer.namedAt[jobs[0].id] ?: Long.MAX_VALUE
    val lastDone = jobs.mapNotNull { j -> store.get(j.id)?.finishedAt }.max()
    check(firstNamed < lastDone, "the first recording was named before the last finished transcribing")
    val titles = store.all().filter { it.naming == "done" }.sortedBy { it.title }.map { it.title }
    println("       ${titles.joinToString(" | ")}")
    check(titles == listOf("Filosofia 01 - Kant", "Filosofia 02 - Platone", "Storia 01 - La rivoluzione francese", "Storia 02 - Napoleone", "Storia 03 - Il Congresso di Vienna"),
        "numbered per subject in recording order")
    check(namer.calls.get() == 6, "a rate-limited naming waited and was retried (${namer.calls.get()} calls for 5 files)")

    // Restart: titles survive, nothing is named or transcribed again.
    store = newStore(dir)
    check(store.all().filter { it.naming == "done" }.map { it.title }.sorted() == titles, "titles saved to disk")
    val namer2 = FakeNamer(store, table)
    eng.calls.set(0)
    runner = JobRunner(store, FakePreparer(300, 10), mapOf("groq" to eng), { QueueSettings() }, materializer = materializer, namer = namer2)
    w = Watch(store); runner.listener = w
    runner.start()
    check(waitIdle(w, 5_000) && namer2.calls.get() == 0 && eng.calls.get() == 0, "after a restart nothing is redone")

    // A library job interrupted before it was read in is not lost on restart.
    val dir2 = tmpDir("lib2")
    val s2 = newStore(dir2)
    s2.add(Job("x", 1).apply { name = "a.m4a"; mediaUri = "content://media/external/audio/media/9" })
    s2.add(Job("y", 1).apply { name = "b.m4a" })   // a share whose copy never finished
    val again = newStore(dir2)
    check(again.get("x")!!.status == Status.WAITING && again.get("y")!!.status == Status.FAILED,
        "an unread library file waits; an unfinished share copy is reported")
}
