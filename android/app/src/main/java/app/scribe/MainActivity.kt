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
import android.provider.MediaStore
import android.provider.OpenableColumns
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

    /** Groq refuses audio over 25MB. */
    private val UPLOAD_LIMIT = 24L * 1024 * 1024

    /** Shares that arrived before the page was ready to receive them. */
    private var pendingShare: List<Uri> = emptyList()
    private var pageReady = false

    /** The page's pending <input type="file">, waiting on the picker. */
    private var fileChooser: ValueCallback<Array<Uri>>? = null

    /** The picker opened by the page's own button, rather than by an input. */
    private val pickForBridge = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uris = if (result.resultCode == RESULT_OK) urisFrom(result.data) else emptyList()
        if (uris.isEmpty()) toJs("window.__scribeFileCancelled && window.__scribeFileCancelled()")
        else copyIn(uris)
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
                    // A share can arrive before there is anything to hand it to.
                    if (pendingShare.isNotEmpty()) {
                        val queued = pendingShare
                        pendingShare = emptyList()
                        copyIn(queued)
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

        handleShare(intent)

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
        if (uris.isEmpty()) return

        if (pageReady) copyIn(uris) else pendingShare = uris
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
        toJs("window.__scribeState && window.__scribeState(${RecordingService.isRunning})")
    }

    override fun onPause() {
        RecordingService.levelListener = null
        super.onPause()
    }

    override fun onDestroy() {
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

    private fun displayName(uri: Uri): String {
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) return c.getString(i) ?: "recording"
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "recording"
    }

    /**
     * Copies the pick into internal storage under a name of our own choosing —
     * a display name is arbitrary text and has no business becoming a path —
     * and hands the page a URL it can fetch.
     */
    /**
     * Copies each pick into internal storage and hands the page a list of URLs
     * it can fetch.
     *
     * Names are ours, not the file's: a display name is arbitrary text and has
     * no business becoming a path. The original travels alongside, because
     * Whisper infers the container from it. The copy runs off the main thread
     * — a batch of lectures is hundreds of megabytes, and copying that where
     * the UI runs would freeze the app long enough to be killed.
     */
    private fun copyIn(uris: List<Uri>) {
        if (uris.isEmpty()) return
        thread(isDaemon = true) {
            // Anything left from a previous batch the page has finished with.
            runCatching { imports.listFiles()?.forEach { it.delete() } }

            val out = JSONArray()
            uris.forEachIndexed { i, uri ->
                val name = displayName(uri)
                val ext = name.substringAfterLast('.', "")
                    .takeIf { it.length in 1..5 && it.all(Char::isLetterOrDigit) } ?: "m4a"
                val dest = File(imports, "pick-${System.currentTimeMillis()}-$i.$ext")
                val ok = runCatching {
                    contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    } ?: return@runCatching false
                    dest.length() > 0
                }.getOrDefault(false)

                if (!ok) {
                    dest.delete()
                    return@forEachIndexed
                }

                val entry = JSONObject().put("name", name)
                if (dest.length() > UPLOAD_LIMIT) {
                    // Too big to send whole. Cut it here rather than in the
                    // page: the browser has to decode the entire recording to
                    // do it, which a long lecture will not survive.
                    val parts = AudioSplitter.split(dest)
                    dest.delete()
                    if (parts.isEmpty()) {
                        out.put(entry.put("error", "This recording could not be split into uploadable pieces."))
                        return@forEachIndexed
                    }
                    val arr = JSONArray()
                    parts.forEach { part ->
                        arr.put(
                            JSONObject()
                                .put("url", "https://appassets.androidplatform.net/imports/${part.file.name}")
                                .put("release", part.file.name)
                                .put("offsetMs", part.startMs),
                        )
                    }
                    entry.put("parts", arr)
                } else {
                    entry.put("url", "https://appassets.androidplatform.net/imports/${dest.name}")
                    entry.put("release", dest.name)
                }
                out.put(entry)
            }

            runOnUiThread {
                if (out.length() == 0) {
                    toJs("window.__scribeError && window.__scribeError(${json("Those files could not be read.")})")
                } else {
                    toJs("window.__scribeFilesPicked && window.__scribeFilesPicked($out)")
                }
            }
        }
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
                copyIn(ids.map { ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it) })
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

        @JavascriptInterface
        fun stopRecording() {
            runOnUiThread {
                RecordingService.stop(this@MainActivity)
                toJs("window.__scribeState && window.__scribeState(false)")
            }
        }
    }
}
