package org.cassini.android.processing

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.cassini.android.R
import org.cassini.android.stringRes
import org.cassini.core.Failure
import org.cassini.core.export.ExportClock
import org.cassini.core.models.ModelCatalog
import org.cassini.core.models.ModelPart
import org.cassini.engine.job.DownloadError
import org.cassini.engine.job.DownloadProgress
import org.cassini.engine.job.JobFailure
import org.cassini.engine.job.JobPhase
import org.cassini.engine.job.ProgressHeadline
import org.cassini.engine.job.RunningProgress
import org.cassini.engine.job.TimeLeft
import kotlin.math.roundToLong

/**
 * The processing notification and the results notifications (architecture 5.10, features.md section 12).
 *
 * Every text comes from `ContextCompat.getContextForLanguage`, so it follows the app language also below API 33.
 * Every notification is VISIBILITY_PRIVATE with a public version without the note title. PendingIntents are
 * `FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT`. Activity intents open the app's launcher activity with [EXTRA_OPEN] and
 * [EXTRA_NOTE_ID] (architecture section 6).
 */
@Suppress("TooManyFunctions") // one function per notification of features.md section 12, plus their text
class ProcessingNotifications(context: Context) : ProcessingNotices {
    private val app = context.applicationContext

    /** A context whose resources use the app language. */
    private fun localized(): Context = ContextCompat.getContextForLanguage(app)

    // The ongoing notification. -----------------------------------------------------------------------------------

    /** The title and text of the ongoing notification for [state]. */
    data class Content(
        val title: String,
        val text: String?,
        val subText: String?,
        val percent: Int?,
        val cancel: Boolean,
    )

    fun content(state: ProcessingState): Content {
        val res = localized()
        val running = state.running
        val install = state.install as? InstallState.Installing
        val waiting = state.waiting.takeIf { it > 0 }
            ?.let { res.resources.getQuantityString(R.plurals.notif_queue_more, it, it) }
        val phase = running?.phase
        return when {
            phase is JobPhase.Downloading -> download(res, phase.progress, waiting)
            running == null && install != null -> download(res, install.progress, waiting)
            running == null -> Content(res.getString(R.string.notif_processing_idle), null, waiting, null, false)
            else -> Content(
                res.getString(R.string.notif_processing_title, running.title),
                phaseText(res, running.phase),
                waiting,
                (running.phase as? JobPhase.Transcribing)?.progress?.percent,
                cancel = false,
            )
        }
    }

    /** The ongoing notification (id [PROCESSING_ID]) with Stop, Open and, while downloading, Cancel. */
    fun ongoing(state: ProcessingState): Notification {
        val res = localized()
        val content = content(state)
        val public = NotificationCompat.Builder(res, NotificationChannels.PROCESSING)
            .setSmallIcon(R.drawable.notif_ic_stat_logo)
            .setContentTitle(res.getString(R.string.notif_processing_public))
            .build()
        val builder = NotificationCompat.Builder(res, NotificationChannels.PROCESSING)
            .setSmallIcon(R.drawable.notif_ic_stat_logo)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setSubText(content.subText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openIntent(state.running?.noteId))
            .setProgress(PERCENT, content.percent ?: 0, content.percent == null)
        if (state.running != null || state.queue.isNotEmpty()) {
            val stop = serviceIntent(ProcessingService.ACTION_STOP_ALL)
            builder.addAction(0, res.getString(R.string.notif_action_stop), stop)
        }
        builder.addAction(0, res.getString(R.string.notif_action_open), openIntent(state.running?.noteId))
        if (content.cancel) {
            builder.addAction(
                0,
                res.getString(R.string.notif_action_cancel),
                serviceIntent(ProcessingService.ACTION_CANCEL_DOWNLOAD),
            )
        }
        return builder.build()
    }

    private fun download(res: Context, progress: DownloadProgress?, waiting: String?): Content {
        if (progress == null) {
            val part = res.getString(partName(ModelPart.SPEECH))
            return Content(res.getString(R.string.notif_processing_downloading, part, 0), null, waiting, null, true)
        }
        val title = if (progress.verifying) {
            res.getString(R.string.notif_processing_checking, progress.percent)
        } else {
            val part = res.getString(partName(progress.part))
            res.getString(R.string.notif_processing_downloading, part, progress.percent)
        }
        val bytes = if (progress.verifying) {
            null
        } else {
            res.getString(
                R.string.notif_processing_download_bytes,
                // The count is exact ("296 of 640 MB"); the total is the size shown everywhere else.
                (progress.doneBytes.coerceAtLeast(0) / MIB).toInt(),
                ModelCatalog.mebibytes(progress.totalBytes.coerceAtLeast(0)).toInt(),
            )
        }
        return Content(title, bytes, waiting, progress.percent, cancel = true)
    }

    private fun phaseText(res: Context, phase: JobPhase): String = when (phase) {
        JobPhase.Starting -> res.getString(R.string.notif_processing_starting)
        JobPhase.WaitingForPrevious -> res.getString(R.string.notif_processing_waiting)
        JobPhase.Stopping -> res.getString(R.string.notif_processing_stopping)
        is JobPhase.Downloading -> res.getString(R.string.notif_processing_starting)
        is JobPhase.Transcribing -> progressText(res, phase.progress)
    }

    /** "62% · 6–9 min left", "Calculating time left…", "Matching voices…", "Saving…". */
    fun progressText(res: Context, progress: RunningProgress): String {
        val percent = progress.percent
        return when (progress.headline) {
            ProgressHeadline.MATCHING_VOICES -> res.getString(R.string.notif_processing_matching)
            ProgressHeadline.SAVING -> res.getString(R.string.notif_processing_saving)
            else -> when (val left = progress.timeLeft) {
                TimeLeft.Calculating -> res.getString(R.string.notif_processing_calculating)
                TimeLeft.Finishing -> res.getString(R.string.notif_processing_finishing, percent ?: 0)
                is TimeLeft.Range -> {
                    val time = timeLeft(res, left.lowMs, left.highMs)
                    if (percent == null) {
                        res.getString(R.string.notif_processing_left, time)
                    } else {
                        res.getString(R.string.notif_processing_progress, percent, time)
                    }
                }
            }
        }
    }

    // Results notifications (posted by the coordinator only while the app is not visible). ---------------------

    override fun transcribed(noteId: String, title: String, speakers: Int, durationMs: Long) {
        val res = localized()
        val text = res.resources.getQuantityString(
            R.plurals.notif_transcribed_speakers,
            speakers,
            speakers,
            ExportClock.clock(durationMs, durationMs),
        )
        val heading = res.getString(R.string.notif_transcribed_title, title)
        post(noteId, result(res, heading, text, R.string.notif_transcribed_public, noteId))
    }

    override fun failed(noteId: String, title: String, failure: String) {
        val res = localized()
        val heading = res.getString(R.string.notif_failed_title, title)
        post(noteId, result(res, heading, failureText(res, failure), R.string.notif_failed_public, noteId))
    }

    override fun readyToTranscribe(noteId: String, title: String) {
        val res = localized()
        val notification = result(
            res,
            res.getString(R.string.notif_ready_title),
            res.getString(R.string.notif_ready_text, title),
            R.string.notif_ready_public,
            noteId,
        )
        post(noteId, notification)
    }

    override fun pausedByAndroid(noteId: String, title: String, doneMs: Long, totalMs: Long) {
        val res = localized()
        val text = res.getString(
            R.string.notif_paused_text,
            ExportClock.clock(doneMs, totalMs),
            ExportClock.clock(totalMs, totalMs),
        )
        val heading = res.getString(R.string.notif_paused_title)
        val notification = resultBuilder(res, heading, text, R.string.notif_paused_public, noteId)
            .addAction(0, res.getString(R.string.notif_action_resume), openIntent(noteId, resume = true))
            .build()
        post(noteId, notification)
    }

    override fun interrupted(noteId: String, title: String) {
        val res = localized()
        val resume = PendingIntent.getForegroundService(
            app,
            noteId.hashCode(),
            ProcessingService.resumeIntent(app, noteId),
            FLAGS,
        )
        val notification = resultBuilder(
            res,
            res.getString(R.string.notif_stopped_title),
            res.getString(R.string.notif_stopped_text, title),
            R.string.notif_stopped_public,
            noteId,
        ).addAction(0, res.getString(R.string.notif_action_resume), resume).build()
        post(noteId, notification)
    }

    /** The text of a stored `jobs.failure` code. */
    fun failureText(res: Context, code: String): String {
        val download = JobFailure.downloadError(code)
        return when {
            download == DownloadError.NO_CONNECTION -> res.getString(R.string.notif_failed_no_connection)
            download != null -> res.getString(ModelInstallOwner.failureOf(download).stringRes)
            code == JobFailure.CheckpointMismatch.code -> res.getString(R.string.notif_failed_mismatch)
            code.startsWith(JobFailure.DOCUMENT_PREFIX) -> res.getString(R.string.notif_failed_document)
            else -> res.getString((Failure.entries.firstOrNull { it.name == code } ?: Failure.UNKNOWN).stringRes)
        }
    }

    private fun result(res: Context, title: String, text: String, publicTitle: Int, noteId: String): Notification =
        resultBuilder(res, title, text, publicTitle, noteId).build()

    private fun resultBuilder(
        res: Context,
        title: String,
        text: String,
        publicTitle: Int,
        noteId: String,
    ): NotificationCompat.Builder {
        val public = NotificationCompat.Builder(res, NotificationChannels.RESULTS)
            .setSmallIcon(R.drawable.notif_ic_stat_logo)
            .setContentTitle(res.getString(publicTitle))
            .build()
        return NotificationCompat.Builder(res, NotificationChannels.RESULTS)
            .setSmallIcon(R.drawable.notif_ic_stat_logo)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(openIntent(noteId))
    }

    /** Posts [notification] for [noteId]: one results notification per note, the newest replaces the older one. */
    private fun post(noteId: String, notification: Notification) {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) NotificationManagerCompat.from(app).notify(noteId, RESULT_ID, notification)
    }

    /** The app's launcher activity, asked to open [noteId] (or the Library), and to resume it with [resume]. */
    private fun openIntent(noteId: String?, resume: Boolean = false): PendingIntent {
        val launch = app.packageManager.getLaunchIntentForPackage(app.packageName)
        val intent = (launch ?: Intent().setPackage(app.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (noteId != null) intent.putExtra(EXTRA_OPEN, OPEN_NOTE).putExtra(EXTRA_NOTE_ID, noteId)
        if (resume) intent.putExtra(EXTRA_RESUME, true)
        // Extras do not tell PendingIntents apart: the request code keeps one per note and action.
        return PendingIntent.getActivity(app, noteId.hashCode() + if (resume) 1 else 0, intent, FLAGS)
    }

    private fun serviceIntent(action: String): PendingIntent =
        PendingIntent.getService(app, action.hashCode(), processingIntent(app, action), FLAGS)

    companion object {
        /** The ongoing processing notification (the recording one is 1). */
        const val PROCESSING_ID = 2

        /** The results notifications, one per note (tag = note id). */
        const val RESULT_ID = 3

        const val EXTRA_OPEN = "open"
        const val OPEN_NOTE = "note"
        const val EXTRA_NOTE_ID = "noteId"
        const val EXTRA_RESUME = "resume"

        private const val PERCENT = 100
        private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        private const val MINUTE_MS = 60_000.0
        private const val MIB = 1_048_576L
        private const val MINUTES_PER_HOUR = 60L

        fun partName(part: ModelPart): Int = when (part) {
            ModelPart.SPEECH -> R.string.notif_part_speech
            ModelPart.SPEAKERS -> R.string.notif_part_speakers
            ModelPart.VOICES -> R.string.notif_part_voices
        }

        /** "6–9 min", "under 1 min", "about 6 min", "1 h 10 min–1 h 30 min" (features.md section 3.1). */
        fun timeLeft(res: Context, lowMs: Long, highMs: Long): String {
            if (highMs < MINUTE_MS) return res.getString(R.string.notif_time_under_minute)
            val low = minutes(lowMs)
            val high = minutes(highMs).coerceAtLeast(low)
            return when {
                low == high -> res.getString(R.string.notif_time_about, duration(res, low))
                high < MINUTES_PER_HOUR -> res.getString(R.string.notif_time_minutes_range, low.toInt(), high.toInt())
                else -> res.getString(R.string.notif_time_range, duration(res, low), duration(res, high))
            }
        }

        private fun minutes(ms: Long): Long = (ms / MINUTE_MS).roundToLong().coerceAtLeast(1)

        private fun duration(res: Context, minutes: Long): String {
            val hours = minutes / MINUTES_PER_HOUR
            val rest = minutes % MINUTES_PER_HOUR
            return when {
                hours == 0L -> res.getString(R.string.notif_time_minutes, rest.toInt())
                rest == 0L -> res.getString(R.string.notif_time_hours, hours.toInt())
                else -> res.getString(R.string.notif_time_hours_minutes, hours.toInt(), rest.toInt())
            }
        }
    }
}
