package org.cassini.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

/**
 * Runs transcription and speaker identification in the foreground, so a long recording keeps
 * processing with the screen off or another app in front, and a screen closing never cancels it.
 * One job at a time. Progress, settled words and checkpoints go through [ProcessingJobs] and the
 * [ProcessingPipeline]; a process killed mid-job resumes from them the next time Cassini opens.
 *
 * The service type is dataSync (processing the user's own data on the device). It must be started
 * while Cassini is in the foreground, which every caller is: a tap, or a screen opening.
 */
class ProcessingService : Service() {
    private var worker: Thread? = null
    @Volatile private var pipeline: ProcessingPipeline? = null
    @Volatile private var cancelled = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var notified = 0L

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))
    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> {
                val noteId = intent.getStringExtra(NOTE_ID)
                if (worker == null && noteId != null) begin(ProcessingJobs.interrupted(filesDir)?.takeIf { it.noteId == noteId }
                    ?.copy(speakers = speakers(intent), failure = null)
                    ?: ProcessingJob(noteId, intent.getStringExtra(NAME).orEmpty(), speakers(intent)))
                else if (worker == null) stopSelf(startId)
            }
            CANCEL -> cancel()
            else -> if (worker == null) stopSelf(startId)
        }
        // A restart from the background could not call startForeground: the next app open resumes instead.
        return START_NOT_STICKY
    }

    private fun speakers(intent: Intent) = intent.getBooleanExtra(SPEAKERS, false)

    private fun begin(initial: ProcessingJob) {
        val job = initial.copy(attempts = initial.attempts + 1, phase = ProcessingJob.Phase.QUEUED, words = emptyList())
        startInForeground(job)
        cancelled = false
        ProcessingJobs.running = true
        ProcessingJobs.update(filesDir, job, persist = true)
        val power = getSystemService(PowerManager::class.java)
        // Bounded so a wedged native call cannot hold the CPU awake for ever.
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Cassini:processing").apply {
            setReferenceCounted(false); acquire(WAKE_LOCK_MS)
        }
        val work = ProcessingPipeline(this) { state, persist -> progress(state, persist) }
        pipeline = work
        worker = Thread({
            var last = job
            val finished = try {
                work.run(job).also { last = it }
            } catch (error: Throwable) {
                val failure = when {
                    cancelled -> Failure.CANCELLED
                    error is UserFacingException -> error.failure
                    error is OutOfMemoryError -> Failure.MEMORY
                    error is java.io.IOException -> Failure.OPEN
                    else -> Failure.UNKNOWN
                }
                if (failure != Failure.CANCELLED) Log.e(TAG, "Processing failed: $failure", error)
                val current = ProcessingJobs.current?.takeIf { it.noteId == job.noteId } ?: last
                current.copy(phase = if (failure == Failure.CANCELLED) ProcessingJob.Phase.CANCELLED else ProcessingJob.Phase.FAILED,
                    failure = failure, words = emptyList())
            }
            finish(finished)
        }, "cassini-processing")
        worker?.start()
    }

    private fun startInForeground(job: ProcessingJob) {
        val notification = notification(job)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIFICATION, notification)
    }

    /** From the work thread. The notification moves at most once a second. */
    private fun progress(job: ProcessingJob, persist: Boolean) {
        ProcessingJobs.update(filesDir, job, persist)
        val now = System.nanoTime()
        if (persist || now - notified >= 1_000_000_000L) {
            notified = now
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(job))
        }
    }

    private fun finish(job: ProcessingJob) {
        ProcessingJobs.update(filesDir, job, persist = true)
        ProcessingJobs.running = false
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        val manager = getSystemService(NotificationManager::class.java)
        stopForeground(STOP_FOREGROUND_REMOVE)
        manager.notify(DONE_NOTIFICATION, Notification.Builder(this, channel()).setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle(job.name.ifBlank { getString(R.string.recording) })
            .setContentText(when (job.phase) {
                ProcessingJob.Phase.DONE -> getString(R.string.processing_done, job.name.substringBeforeLast('.'))
                ProcessingJob.Phase.CANCELLED -> getString(R.string.processing_cancelled)
                else -> if (job.settledWords > 0) resources.getQuantityString(R.plurals.processing_failed_partial, job.settledWords, job.settledWords)
                    else getString(R.string.processing_failed, getString((job.failure ?: Failure.UNKNOWN).stringRes))
            })
            .setContentIntent(open(job.noteId)).setAutoCancel(true).build())
        worker = null; pipeline = null
        stopSelf()
    }

    private fun cancel() {
        cancelled = true
        pipeline?.cancel()
        worker?.interrupt()
    }

    private fun channel(): String {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.processing_channel), NotificationManager.IMPORTANCE_LOW))
        return CHANNEL
    }

    private fun open(noteId: String) = PendingIntent.getActivity(this, noteId.hashCode(),
        Intent(this, MainActivity::class.java).putExtra(MainActivity.NOTE_ID, noteId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun notification(job: ProcessingJob): Notification {
        val title = getString(if (job.phase == ProcessingJob.Phase.DIARIZE) R.string.processing_title_speakers else R.string.processing_title_transcribing,
            job.name.substringBeforeLast('.').ifBlank { getString(R.string.recording) })
        val speed = job.speed
        val remaining = job.remainingMs
        val text = when {
            job.phase == ProcessingJob.Phase.PACKAGE -> getString(R.string.packaging_document)
            job.phase == ProcessingJob.Phase.QUEUED || job.phase == ProcessingJob.Phase.DECODE -> getString(R.string.decoding)
            speed != null && remaining != null && job.doneMs > 0 -> getString(R.string.processing_notification_progress, job.percent, speed, clock(remaining))
            else -> getString(if (job.phase == ProcessingJob.Phase.DIARIZE) R.string.identifying_speakers else R.string.transcribing)
        }
        val stop = PendingIntent.getService(this, 1, Intent(this, ProcessingService::class.java).setAction(CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, channel()).setSmallIcon(R.drawable.ic_stat_record).setOngoing(true)
            .setOnlyAlertOnce(true).setContentTitle(title).setContentText(text)
            .setProgress(100, job.percent, job.doneMs <= 0 || job.phase == ProcessingJob.Phase.PACKAGE)
            .setContentIntent(open(job.noteId))
            .addAction(Notification.Action.Builder(null, getString(R.string.cancel), stop).build())
            .build()
    }

    override fun onDestroy() {
        // The system ends a dataSync service only under pressure; the journal keeps what was settled.
        if (worker != null) cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Cassini"
        private const val CHANNEL = "processing"
        private const val NOTIFICATION = 3
        private const val DONE_NOTIFICATION = 4
        private const val WAKE_LOCK_MS = 4 * 60 * 60 * 1000L
        private const val START = "org.cassini.android.processing.START"
        private const val CANCEL = "org.cassini.android.processing.CANCEL"
        private const val NOTE_ID = "noteId"
        private const val NAME = "name"
        private const val SPEAKERS = "speakers"

        /** Starts processing [noteId]; call while a Cassini screen is in the foreground. */
        fun start(context: Context, noteId: String, name: String, speakers: Boolean) {
            context.startForegroundService(Intent(context, ProcessingService::class.java).setAction(START)
                .putExtra(NOTE_ID, noteId).putExtra(NAME, name).putExtra(SPEAKERS, speakers))
        }

        fun cancel(context: Context) {
            if (ProcessingJobs.running) context.startService(Intent(context, ProcessingService::class.java).setAction(CANCEL))
        }
    }
}
