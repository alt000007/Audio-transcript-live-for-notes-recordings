package app.scribe

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
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

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
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
            }
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    // Only ever the microphone, and only once Android has granted it to us.
                    val wants = request.resources.filter { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }
                    if (wants.isNotEmpty() && hasMic()) request.grant(wants.toTypedArray()) else request.deny()
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

        RecordingService.listener = { seg -> deliver(seg) }
        RecordingService.errorListener = { msg ->
            runOnUiThread { toJs("window.__scribeError && window.__scribeError(${json(msg)})") }
        }
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

        @JavascriptInterface
        fun stopRecording() {
            runOnUiThread {
                RecordingService.stop(this@MainActivity)
                toJs("window.__scribeState && window.__scribeState(false)")
            }
        }
    }
}
