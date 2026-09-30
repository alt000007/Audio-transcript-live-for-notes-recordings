package app.scribe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

/**
 * Captures audio in a foreground service, so recording continues with the
 * screen off and another app in front — the thing a web page on this platform
 * cannot do.
 *
 * Capture, silence detection and encoding happen here; uploading and
 * transcription stay in the web layer, which already does them. Segments are
 * written to disk before anyone is told about them, so a crash or a killed
 * activity costs the transcript, never the recording.
 */
class RecordingService : Service() {

    private val vad = Vad()
    @Volatile private var running = false
    private var worker: Thread? = null

    /** Segments encoded but not yet handed to the web layer. */
    private val pending = ConcurrentLinkedQueue<Segment>()

    data class Segment(val index: Int, val startMs: Long, val endMs: Long, val file: File)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopCapture(); stopSelf(); return START_NOT_STICKY }
            else -> startCapture()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    private fun startCapture() {
        if (running) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        running = true
        instance = this
        worker = thread(name = "scribe-capture", isDaemon = true) { captureLoop() }
    }

    private fun stopCapture() {
        running = false
        worker?.join(2_000)
        worker = null
        if (instance === this) instance = null
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        }
    }

    private fun captureLoop() {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) { notifyError("This device cannot record at 16 kHz."); return }

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf * 4, SAMPLE_RATE),  // ~1s of slack against scheduling hiccups
            )
        } catch (t: Throwable) { notifyError("Microphone unavailable: ${t.message}"); return }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release(); notifyError("Microphone could not be opened."); return
        }

        val block = ShortArray(SAMPLE_RATE / 10)             // 100 ms per read
        val segment = ShortArray(SAMPLE_RATE * MAX_SEG_SEC)  // worst-case segment
        var segLen = 0
        var index = 0
        var totalSamples = 0L
        var segStartMs = 0L
        var silenceMs = 0
        var speechMs = 0
        var sawSpeech = false

        recorder.startRecording()
        vad.resetPeak()

        try {
            while (running) {
                val read = recorder.read(block, 0, block.size)
                if (read <= 0) continue

                vad.push(block, read)
                if (segLen + read <= segment.size) {
                    System.arraycopy(block, 0, segment, segLen, read)
                    segLen += read
                }
                totalSamples += read

                val blockMs = read * 1000 / SAMPLE_RATE
                if (vad.speaking) { sawSpeech = true; speechMs += blockMs.toInt(); silenceMs = 0 } else silenceMs += blockMs.toInt()

                val nowMs = totalSamples * 1000 / SAMPLE_RATE
                val durMs = nowMs - segStartMs
                val atPause = sawSpeech && durMs >= MIN_SEG_MS && silenceMs >= SILENCE_HOLD_MS
                val atCap = durMs >= MAX_SEG_SEC * 1000

                if (atPause || atCap) {
                    // A pause handed to Whisper comes back as an ellipsis or
                    // invented filler, so require a real amount of speech. The
                    // loudness escape hatch keeps a segment the detector
                    // misjudged: losing part of a lecture costs far more than
                    // one wasted upload.
                    if (speechMs >= MIN_SPEECH_MS || vad.peak > Vad.AUDIBLE * 4) {
                        emit(index++, segStartMs, nowMs, segment, segLen)
                    }
                    segLen = 0; segStartMs = nowMs; silenceMs = 0; sawSpeech = false; speechMs = 0
                    vad.resetPeak()
                }
            }

            // Flush whatever the final partial segment holds.
            val nowMs = totalSamples * 1000 / SAMPLE_RATE
            if (segLen > 0 && (speechMs >= MIN_SPEECH_MS || vad.peak > Vad.AUDIBLE * 4)) {
                emit(index, segStartMs, nowMs, segment, segLen)
            }
        } catch (t: Throwable) {
            notifyError("Recording stopped: ${t.message}")
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }
    }

    private fun emit(index: Int, startMs: Long, endMs: Long, pcm: ShortArray, length: Int) {
        val dir = File(cacheDir, "segments").apply { mkdirs() }
        val file = File(dir, "seg-%05d.m4a".format(index))
        if (!AacEncoder.encode(pcm, length, SAMPLE_RATE, file)) {
            file.delete()
            return
        }
        val seg = Segment(index, startMs, endMs, file)
        pending.add(seg)
        listener?.invoke(seg)
    }

    private fun notifyError(message: String) {
        errorListener?.invoke(message)
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while Scribe is recording"
                    setShowBadge(false)
                },
            )
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setContentTitle("Scribe is recording")
            .setContentText("Tap to open")
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        const val ACTION_START = "app.scribe.START"
        const val ACTION_STOP = "app.scribe.STOP"
        private const val CHANNEL = "scribe-recording"
        private const val NOTIFICATION_ID = 1
        const val SAMPLE_RATE = 16_000
        private const val MIN_SEG_MS = 4_000
        private const val MAX_SEG_SEC = 12
        private const val SILENCE_HOLD_MS = 550
        private const val MIN_SPEECH_MS = 400

        @Volatile private var instance: RecordingService? = null

        /** Called on the capture thread as each segment lands. */
        @Volatile var listener: ((Segment) -> Unit)? = null
        @Volatile var errorListener: ((String) -> Unit)? = null

        val isRunning: Boolean get() = instance?.running == true

        /** Segments encoded while nothing was listening, so reopening the app
         *  picks up everything recorded while it was away. */
        fun drainPending(): List<Segment> {
            val svc = instance ?: return emptyList()
            val out = ArrayList<Segment>()
            while (true) out.add(svc.pending.poll() ?: break)
            return out
        }

        fun start(context: Context) {
            val intent = Intent(context, RecordingService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_STOP))
        }
    }
}
