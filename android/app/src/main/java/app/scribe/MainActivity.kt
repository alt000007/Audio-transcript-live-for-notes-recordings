package app.scribe

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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

    /** A share that arrived before the page was ready to receive it. */
    private var pendingShare: Uri? = null
    private var pageReady = false

    /** The page's pending <input type="file">, waiting on the picker. */
    private var fileChooser: ValueCallback<Array<Uri>>? = null

    /** The picker opened by the page's own button, rather than by an input. */
    private val pickForBridge = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode != RESULT_OK || uri == null) {
            toJs("window.__scribeFileCancelled && window.__scribeFileCancelled()")
        } else {
            copyIn(uri)
        }
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
                    pendingShare?.let { pendingShare = null; copyIn(it) }
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
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_SEND ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        } ?: return

        if (pageReady) copyIn(uri) else pendingShare = uri
    }

    override fun onResume() {
        super.onResume()
        // Anything captured while the activity was gone.
        RecordingService.drainPending().forEach { deliver(it) }
        toJs("window.__scribeState && window.__scribeState(${RecordingService.isRunning})")
    }

    override fun onDestroy() {
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
        putExtra(
            Intent.EXTRA_MIME_TYPES,
            arrayOf("audio/*", "video/mp4", "application/ogg", "application/octet-stream"),
        )
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
    private fun copyIn(uri: Uri) {
        // Off the main thread: a lecture can be tens of megabytes, and copying
        // that where the UI runs would freeze the app long enough to be killed.
        thread(isDaemon = true) {
            val name = displayName(uri)
            val ext = name.substringAfterLast('.', "").takeIf { it.length in 1..5 && it.all(Char::isLetterOrDigit) } ?: "m4a"
            val dest = File(imports, "pick-${System.currentTimeMillis()}.$ext")

            val ok = runCatching {
                imports.listFiles()?.forEach { it.delete() }   // only ever one at a time
                contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                } ?: return@runCatching false
                dest.length() > 0
            }.getOrDefault(false)

            runOnUiThread {
                if (!ok) {
                    dest.delete()
                    toJs("window.__scribeError && window.__scribeError(${json("That file could not be read.")})")
                } else {
                    val url = "https://appassets.androidplatform.net/imports/${dest.name}"
                    toJs("window.__scribeFilePicked && window.__scribeFilePicked(${json(url)}, ${json(name)})")
                }
            }
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

        @JavascriptInterface
        fun stopRecording() {
            runOnUiThread {
                RecordingService.stop(this@MainActivity)
                toJs("window.__scribeState && window.__scribeState(false)")
            }
        }
    }
}
