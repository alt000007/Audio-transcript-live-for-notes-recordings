package app.scribe

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * Hosts the existing web app and gives it a microphone that keeps working when
 * the phone is locked.
 *
 * The page is served through WebViewAssetLoader rather than a file:// URL, so
 * it runs on an https origin. That matters for more than tidiness: a file://
 * page has a null origin, which breaks CORS against the transcription API, and
 * it is not a secure context.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var pendingStart = false

    private lateinit var imports: File

    private var pageReady = false
    /** A notification asked to open a transcript before the page had loaded. */
    private var pendingOpenJob: String? = null

    private val ui = Handler(Looper.getMainLooper())
    private var queuePushQueued = false

    /** The page's pending <input type="file">, waiting on the picker. */
    private var fileChooser: ValueCallback<Array<Uri>>? = null

    /** The picker opened by the page's own button, rather than by an input. */
    private val pickForBridge = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uris = if (result.resultCode == RESULT_OK) urisFrom(result.data) else emptyList()
        if (uris.isEmpty()) toJs("window.__scribeFileCancelled && window.__scribeFileCancelled()")
        else addToQueue(uris)
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val cb = fileChooser
        fileChooser = null
        // parseResult yields null when the user backed out, and the callback
        // must still be answered — leaving it unanswered wedges the input so
        // every later tap does nothing at all.
        cb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
    }

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && pendingStart) beginRecording() else if (!granted) {
            toJs("window.__scribeError && window.__scribeError(${json("Microphone permission was declined. Scribe cannot record without it.")})")
        }
        pendingStart = false
    }

    private val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val mediaPermission
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
                else Manifest.permission.READ_EXTERNAL_STORAGE

    private val askMedia = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) emitRecordings()
        else toJs("window.__scribeNoMediaAccess && window.__scribeNoMediaAccess()")
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        imports = File(cacheDir, "imports").apply { mkdirs() }

        // The file queue lives on disk; load it and carry on with anything
        // left unfinished by a crash, a reboot or the app being closed.
        Queue.init(this)
        Queue.pageListener = { pushQueue() }
        Queue.duplicateListener = { name, existing ->
            runOnUiThread {
                toJs("window.__scribeDuplicate && window.__scribeDuplicate(${json(name)}, ${json(existing.id)}, ${json(existing.status)})")
            }
        }

        // A picked recording can be tens of megabytes. Handing that to the page
        // as base64 through the JavaScript bridge would mean holding several
        // copies in memory at once; serving it over the app's own origin lets
        // the page stream it with fetch() instead.
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/imports/", WebViewAssetLoader.InternalStoragePathHandler(this, imports))
            .build()

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true        // the app keeps sessions in localStorage
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    loader.shouldInterceptRequest(request.url)

                override fun onPageFinished(view: WebView, url: String) {
                    pageReady = true
                    Diag.log("page", "loaded")
                    pushQueue()
                    pendingOpenJob?.let { id ->
                        pendingOpenJob = null
                        toJs("window.__scribeOpenJob && window.__scribeOpenJob(${json(id)})")
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    // Only ever the microphone, and only once Android has granted it to us.
                    val wants = request.resources.filter { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }
                    if (wants.isNotEmpty() && hasMic()) request.grant(wants.toTypedArray()) else request.deny()
                }

                /**
                 * A WebView has no file picker of its own: without this, the
                 * page's file input silently does nothing when tapped.
                 */
                override fun onShowFileChooser(
                    view: WebView,
                    callback: ValueCallback<Array<Uri>>,
                    params: WebChromeClient.FileChooserParams,
                ): Boolean {
                    fileChooser?.onReceiveValue(null)   // abandon any earlier one
                    fileChooser = callback
                    for (action in listOf(Intent.ACTION_OPEN_DOCUMENT, Intent.ACTION_GET_CONTENT)) {
                        try {
                            pickFile.launch(pickIntent(action))
                            return true
                        } catch (e: ActivityNotFoundException) {
                            // Try the next one.
                        }
                    }
                    fileChooser = null
                    callback.onReceiveValue(null)
                    toJs("window.__scribeError && window.__scribeError(${json("No app on this phone can pick a file.")})")
                    return false
                }
            }
            addJavascriptInterface(Bridge(), "ScribeNative")
        }
        setContentView(web)
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // A recreated activity is handed its original intent again; the share
        // in it was already queued the first time round.
        if (savedInstanceState == null) handleShare(intent)
        Queue.resume(this)

        RecordingService.listener = { seg -> deliver(seg) }
        RecordingService.errorListener = { msg ->
            runOnUiThread { toJs("window.__scribeError && window.__scribeError(${json(msg)})") }
        }
    }

    /** singleTask means a second share reuses this activity rather than
     *  starting another, so it arrives here instead of onCreate. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    /** Picks the audio out of a share or an open request, if there is one. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action == TranscribeService.ACTION_OPEN) {
            val id = intent.getStringExtra(TranscribeService.EXTRA_JOB) ?: return
            if (pageReady) toJs("window.__scribeOpenJob && window.__scribeOpenJob(${json(id)})") else pendingOpenJob = id
            return
        }
        val uris: List<Uri> = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM),
            )
            Intent.ACTION_SEND_MULTIPLE ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
                else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
        Diag.log("share", "${intent?.action} -> ${uris.size} uri(s), pageReady=$pageReady")
        if (uris.isEmpty()) return
        // A share is consumed once; a configuration change or a return to the
        // app must not queue the same files again.
        intent?.action = Intent.ACTION_MAIN

        // Say so immediately, then copy. The queue does not need the page, so
        // a share is never held back waiting for it to load.
        val names = uris.joinToString(", ") { Queue.displayName(this, it) }
        toJs("window.__scribeShareIncoming && window.__scribeShareIncoming(${json(names)}, ${uris.size})")
        addToQueue(uris)
    }

    private fun addToQueue(uris: List<Uri>) {
        Queue.addFiles(this, uris)
        pushQueue()
    }

    /** Repaints the page's queue list, at most four times a second. */
    private fun pushQueue() {
        ui.post {
            if (queuePushQueued) return@post
            queuePushQueued = true
            ui.postDelayed({
                queuePushQueued = false
                if (pageReady) toJs("window.__scribeQueue && window.__scribeQueue(${Queue.snapshot()})")
            }, 250)
        }
    }

    override fun onResume() {
        super.onResume()
        // Only feed the meter while there is a screen to show it on; it is
        // several calls a second into the WebView.
        RecordingService.levelListener = { level, speaking ->
            runOnUiThread { toJs("window.__scribeLevel && window.__scribeLevel($level, $speaking)") }
        }
        // Anything captured while the activity was gone.
        RecordingService.drainPending().forEach { deliver(it) }
        // Unfinished files carry on, and the list catches up with whatever
        // happened while the app was away.
        Queue.resume(this)
        pushQueue()
        toJs("window.__scribeState && window.__scribeState(${RecordingService.isRunning})")
    }

    override fun onPause() {
        RecordingService.levelListener = null
        super.onPause()
    }

    override fun onDestroy() {
        if (Queue.pageListener != null) Queue.pageListener = null
        Queue.duplicateListener = null
        RecordingService.levelListener = null
        RecordingService.listener = null
        RecordingService.errorListener = null
        // The WebView is not destroyed while a recording is running, so the
        // upload loop keeps going; the service owns the microphone either way.
        super.onDestroy()
    }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun beginRecording() {
        RecordingService.start(this)
        toJs("window.__scribeState && window.__scribeState(true)")
    }

    /** Hands one encoded segment to the page, which uploads it as usual. */
    private fun deliver(seg: RecordingService.Segment) {
        val bytes = runCatching { seg.file.readBytes() }.getOrNull() ?: return
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        seg.file.delete()
        Diag.segmentsDelivered++
        runOnUiThread {
            toJs("window.__scribeSegment && window.__scribeSegment(${seg.index}, ${seg.startMs}, ${seg.endMs}, ${json(b64)})")
        }
    }

    /** Builds the picker intent, preferring the Storage Access Framework. */
    private fun pickIntent(action: String) = Intent(action).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        // Filtering strictly on audio/* greys out recordings saved with a
        // vague type, which is common enough to look like a broken picker.
        type = "*/*"
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        putExtra(
            Intent.EXTRA_MIME_TYPES,
            arrayOf("audio/*", "video/mp4", "application/ogg", "application/octet-stream"),
        )
    }

    /** A result may carry one uri in the data, or several in the clip data. */
    private fun urisFrom(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        intent.clipData?.let { clip ->
            return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        }
        return listOfNotNull(intent.data)
    }

    private fun launchPicker(): Boolean {
        for (action in listOf(Intent.ACTION_OPEN_DOCUMENT, Intent.ACTION_GET_CONTENT)) {
            try {
                pickForBridge.launch(pickIntent(action))
                return true
            } catch (e: ActivityNotFoundException) {
                // Try the next one: a few devices ship without one or the other.
            }
        }
        return false
    }

    private fun hasMediaAccess() =
        ContextCompat.checkSelfPermission(this, mediaPermission) == PackageManager.PERMISSION_GRANTED

    /**
     * Lists the audio already on the phone so the app can show its own
     * chooser. The system document picker is one component among many on a
     * given device and can be absent, replaced or simply uncooperative;
     * reading MediaStore does not depend on any of that.
     */
    private fun emitRecordings() {
        thread(isDaemon = true) {
            val out = JSONArray()
            runCatching {
                val cols = arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.SIZE,
                    MediaStore.Audio.Media.DURATION,
                )
                contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    cols, null, null,
                    "${MediaStore.Audio.Media.DATE_ADDED} DESC",
                )?.use { c ->
                    while (c.moveToNext() && out.length() < 300) {
                        out.put(
                            JSONObject()
                                .put("id", c.getLong(0).toString())
                                .put("name", c.getString(1) ?: "recording")
                                .put("size", c.getLong(2))
                                .put("duration", c.getLong(3)),
                        )
                    }
                }
            }
            runOnUiThread { toJs("window.__scribeRecordings && window.__scribeRecordings($out)") }
        }
    }

    private fun batteryUnrestricted(): Boolean =
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    /**
     * Shares a transcript. Short ones go as plain text; a long lecture is far
     * past what an intent can carry, so it goes as a file instead.
     */
    private fun shareOut(title: String, text: String) {
        val send = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_SUBJECT, title)
        if (text.length < 60_000) {
            send.type = "text/plain"
            send.putExtra(Intent.EXTRA_TEXT, text)
        } else {
            val dir = File(cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val safe = title.filter { it.isLetterOrDigit() || it == ' ' || it == '-' }.trim().take(60).ifEmpty { "transcript" }
            val f = File(dir, "$safe.md")
            f.writeText(text)
            send.type = "text/markdown"
            send.putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(this, "$packageName.files", f))
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(send, title)) }
            .onFailure { toJs("window.__scribeError && window.__scribeError(${json("No app on this phone can receive a share.")})") }
    }

    private fun toJs(script: String) {
        runCatching { web.evaluateJavascript(script, null) }
    }

    /** JSON-quotes a string so it is safe to paste into evaluateJavascript. */
    private fun json(value: String): String = JSONObject.quote(value)

    private inner class Bridge {
        @JavascriptInterface
        fun isNative(): Boolean = true

        @JavascriptInterface
        fun isRecording(): Boolean = RecordingService.isRunning

        @JavascriptInterface
        fun startRecording() {
            runOnUiThread {
                if (hasMic()) beginRecording()
                else { pendingStart = true; askMic.launch(Manifest.permission.RECORD_AUDIO) }
            }
        }

        /** Opens the system picker directly, without going through the page's
         *  file input — which is inert in a WebView unless the host handles it,
         *  and is the part most likely to misbehave. */
        @JavascriptInterface
        fun pickFile() {
            runOnUiThread {
                if (!launchPicker()) {
                    toJs("window.__scribeError && window.__scribeError(${json("No app on this phone can pick a file.")})")
                }
            }
        }

        /** Ask for the phone's own list of recordings. */
        @JavascriptInterface
        fun listRecordings() {
            runOnUiThread {
                if (hasMediaAccess()) emitRecordings() else askMedia.launch(mediaPermission)
            }
        }

        /** Import entries from that list — one id, or a JSON array of them. */
        @JavascriptInterface
        fun openRecordings(idsJson: String) {
            runOnUiThread {
                val ids = runCatching {
                    val a = JSONArray(idsJson)
                    (0 until a.length()).mapNotNull { a.optString(it).toLongOrNull() }
                }.getOrDefault(emptyList())
                if (ids.isEmpty()) return@runOnUiThread
                addToQueue(ids.map { ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it) })
            }
        }

        @JavascriptInterface
        fun openRecording(id: String) = openRecordings("[\"$id\"]")

        /** The page has finished with a copied file, so it can go. */
        @JavascriptInterface
        fun releaseImport(name: String) {
            // Constrain to our own directory: the name comes back through
            // JavaScript and must not be able to point anywhere else.
            val safe = File(imports, File(name).name)
            if (safe.parentFile == imports) safe.delete()
        }

        /** Everything the native side knows about this session, as JSON. */
        @JavascriptInterface
        fun diagnostics(): String = Diag.snapshot(
            mapOf(
                "micPermission" to hasMic(),
                "mediaPermission" to hasMediaAccess(),
                "pageReady" to pageReady,
                "pendingShare" to 0,
                "importsDir" to (if (::imports.isInitialized) imports.listFiles()?.size ?: 0 else -1),
                "queueJobs" to Queue.store.all().size,
                "queueOpen" to Queue.store.all().count { !Status.finished(it.status) },
                "queueRunning" to Queue.runner.isRunning,
                "batteryUnrestricted" to batteryUnrestricted(),
            ),
        )

        /* ── the file queue ── */

        /** The whole list, as JSON. The page also gets this pushed on every change. */
        @JavascriptInterface
        fun queueSnapshot(): String = Queue.snapshot()

        /** One file's transcript so far — complete when it is done, partial while it runs. */
        @JavascriptInterface
        fun jobSegments(id: String): String = Queue.segmentsJson(id)

        @JavascriptInterface
        fun cancelJob(id: String) = Queue.runner.cancel(id)

        @JavascriptInterface
        fun retryJob(id: String) {
            Queue.runner.retry(id)
            Queue.resume(this@MainActivity)
        }

        @JavascriptInterface
        fun moveJob(id: String, delta: Int) = Queue.move(id, delta)

        /** Forgets a file entirely, so sharing it again transcribes it again. */
        @JavascriptInterface
        fun removeJob(id: String) = Queue.remove(id)

        @JavascriptInterface
        fun clearFinished() = Queue.clearFinished()

        /** The page has copied a finished transcript into its library. */
        @JavascriptInterface
        fun markImported(id: String) = Queue.markImported(id)

        @JavascriptInterface
        fun setQueueSettings(json: String) = Queue.applySettings(this@MainActivity, json)

        /* ── the Groq key, kept in the Android Keystore rather than in the page ── */

        @JavascriptInterface
        fun setKey(key: String): Boolean = Queue.setKey(this@MainActivity, key)

        @JavascriptInterface
        fun getKey(): String = Queue.getKey(this@MainActivity)

        /* ── the on-device engine ── */

        @JavascriptInterface
        fun downloadModel(id: String): Boolean = Queue.local.download(id).also { pushQueue() }

        @JavascriptInterface
        fun deleteModel() { Queue.local.deleteModel(); pushQueue() }

        /* ── odds and ends ── */

        /** Hands text to another app through the share sheet. */
        @JavascriptInterface
        fun shareText(title: String, text: String) {
            runOnUiThread { shareOut(title, text) }
        }

        @JavascriptInterface
        fun isBatteryUnrestricted(): Boolean = batteryUnrestricted()

        /** Asks to be left alone by battery optimisation, which on some phones stops background work. */
        @JavascriptInterface
        fun openBatterySettings() {
            runOnUiThread {
                val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                try { startActivity(direct) } catch (e: Exception) {
                    runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                }
            }
        }

        @JavascriptInterface
        fun stopRecording() {
            runOnUiThread {
                RecordingService.stop(this@MainActivity)
                toJs("window.__scribeState && window.__scribeState(false)")
            }
        }
    }
}
