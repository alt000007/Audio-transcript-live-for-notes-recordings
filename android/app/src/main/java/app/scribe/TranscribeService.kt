package app.scribe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/**
 * Keeps the file queue running with the screen off and the app closed.
 *
 * A foreground service with a progress notification is what Android requires
 * for long work the user asked for; without one the process is fair game as
 * soon as the app leaves the screen. The partial wake lock keeps the CPU
 * awake between uploads — a foreground service alone does not.
 *
 * The service holds no state of its own. Everything is in the queue's files,
 * so if Android stops it anyway, reopening the app carries on from the last
 * saved piece.
 */
class TranscribeService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var wake: PowerManager.WakeLock? = null
    private var lastPaint = 0L
    private var paintQueued = false
    /** Files finished since this run started, for the "all done" notice. */
    private var finishedThisRun = 0
    private var failedThisRun = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Queue.init(this)
        channels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = progressNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFY_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFY_PROGRESS, n)
            }
        } catch (e: Exception) {
            // Android refused to let us run in the foreground right now (for
            // example, started from the background). The queue still runs
            // while the app is open, and resumes from disk next time.
            Diag.log("queue", "foreground refused: ${e.message}")
        }
        acquireWake()
        Queue.serviceListener = listener
        Queue.runner.start()
        return START_STICKY
    }

    private val listener = object : JobRunner.Listener {
        override fun onChange() = repaint()

        override fun onFinished(job: Job) {
            if (job.status == Status.DONE) finishedThisRun++ else if (job.status == Status.FAILED) failedThisRun++
            if (Queue.notifyEach && job.status == Status.DONE) {
                notifyDone(job.id.hashCode(), "Transcribed: ${job.name}", "Tap to open the transcript", job.id)
            }
            repaint()
        }

        override fun onIdle() {
            main.post {
                if (Queue.notifyBatch && finishedThisRun + failedThisRun > 0) {
                    val text = if (failedThisRun == 0) "Tap to open your transcripts"
                               else "$failedThisRun could not be finished — tap to see why"
                    val title = if (finishedThisRun == 1 && failedThisRun == 0) "Your transcription is ready"
                                else "$finishedThisRun transcription${if (finishedThisRun == 1) "" else "s"} finished"
                    notifyDone(NOTIFY_BATCH, title, text, null)
                }
                finishedThisRun = 0
                failedThisRun = 0
                // A file may have been shared in the meantime and restarted the queue.
                if (!Queue.runner.isRunning) stopSelf()
            }
        }
    }

    /** Notification updates are throttled: Android drops ones sent too quickly anyway. */
    private fun repaint() {
        main.post {
            val now = System.currentTimeMillis()
            if (now - lastPaint < 1000) {
                if (!paintQueued) {
                    paintQueued = true
                    main.postDelayed({ paintQueued = false; repaint() }, 1000)
                }
                return@post
            }
            lastPaint = now
            runCatching {
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .notify(NOTIFY_PROGRESS, progressNotification())
            }
        }
    }

    private fun progressNotification(): Notification {
        val jobs = Queue.store.all().filter { !Status.finished(it.status) }
        val active = jobs.firstOrNull { it.status == Status.PROCESSING } ?: jobs.firstOrNull()
        val total = jobs.sumOf { it.chunks.size }
        val done = jobs.sumOf { it.chunksDone() }
        val title = when {
            jobs.isEmpty() -> "Scribe"
            jobs.size == 1 -> "Transcribing ${active?.name ?: ""}"
            else -> "Transcribing ${jobs.size} files"
        }
        val text = when {
            active == null -> "Finishing up…"
            active.note.isNotEmpty() -> active.note
            active.chunks.isNotEmpty() -> "${active.name} — part ${active.chunksDone() + 1} of ${active.chunks.size}" +
                if (!active.prepared) "+" else ""
            else -> active.name
        }
        return builder(CHANNEL_PROGRESS)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(maxOf(total, 1), done, total == 0)
            .setContentIntent(openApp(null, 0))
            .build()
    }

    private fun notifyDone(id: Int, title: String, text: String, jobId: String?) {
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(
                id,
                builder(CHANNEL_DONE)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                    .setAutoCancel(true)
                    .setContentIntent(openApp(jobId, id))
                    .build(),
            )
        }
    }

    private fun openApp(jobId: String?, request: Int): PendingIntent {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .setAction(ACTION_OPEN)
        if (jobId != null) i.putExtra(EXTRA_JOB, jobId)
        return PendingIntent.getActivity(this, request, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun builder(channel: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, channel)
        else @Suppress("DEPRECATION") Notification.Builder(this)

    private fun channels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val m = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        m.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Transcription progress", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while files are being transcribed"
                setShowBadge(false)
            },
        )
        m.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Finished transcriptions", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When a file, or a whole batch, has been transcribed"
            },
        )
    }

    private fun acquireWake() {
        if (wake?.isHeld == true) return
        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "scribe:queue").apply {
                setReferenceCounted(false)
                // Long, but bounded: a stuck run cannot hold the phone awake forever.
                acquire(6 * 60 * 60 * 1000L)
            }
    }

    /** Android 15 caps data-sync services at six hours a day. Stop cleanly; the queue resumes on next open. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Diag.log("queue", "foreground time limit reached")
        Queue.runner.stop()
        stopSelf()
    }

    override fun onDestroy() {
        if (Queue.serviceListener === listener) Queue.serviceListener = null
        if (Queue.runner.isRunning && Queue.pageListener == null) Queue.runner.stop()
        runCatching { wake?.release() }
        wake = null
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_OPEN = "app.scribe.OPEN_QUEUE"
        const val EXTRA_JOB = "job"
        private const val CHANNEL_PROGRESS = "scribe-queue"
        private const val CHANNEL_DONE = "scribe-done"
        private const val NOTIFY_PROGRESS = 2
        private const val NOTIFY_BATCH = 3

        fun start(context: Context) {
            val intent = Intent(context, TranscribeService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            } catch (e: Exception) {
                // Not allowed from where we are; run without the service for now.
                Diag.log("queue", "service start refused: ${e.message}")
                Queue.init(context)
                Queue.runner.start()
            }
        }
    }
}
