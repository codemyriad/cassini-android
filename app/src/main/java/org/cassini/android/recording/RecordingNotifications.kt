package org.cassini.android.recording

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
import org.cassini.android.inAppLanguage
import org.cassini.android.processing.NotificationChannels
import org.cassini.android.processing.ProcessingNotifications
import org.cassini.core.export.ExportClock
import org.cassini.data.notes.NoteTitles
import org.cassini.ui.R as UiR

/**
 * The recording notification (id [RECORDING_ID]) and the recording results notifications (features.md section 12).
 * Text follows the app language; every notification is VISIBILITY_PRIVATE with a public version without the title.
 */
@Suppress("TooManyFunctions") // one function per notification of features.md section 12, plus their intents
class RecordingNotifications(context: Context) {
    private val app = context.applicationContext

    private fun localized(): Context = app.inAppLanguage()

    /** What the ongoing notification shows; it is posted again only when this changes. */
    data class Content(val title: String, val text: String?, val pausedAtMs: Long?, val chronometer: Boolean)

    fun content(state: RecordingState): Content {
        val res = localized()
        return when (state) {
            is RecordingState.Active -> {
                val paused = state.pause != null
                val heading = res.getString(if (paused) R.string.notif_rec_paused_title else R.string.notif_rec_title)
                Content(
                    title = heading,
                    text = warningText(res, state) ?: NoteTitles.clean(state.title) ?: state.defaultTitle,
                    pausedAtMs = state.elapsedMs.takeIf { paused },
                    chronometer = !paused,
                )
            }
            is RecordingState.Finishing -> plain(res, R.string.notif_rec_saving)
            else -> plain(res, R.string.notif_rec_starting)
        }
    }

    private fun plain(res: Context, text: Int) =
        Content(res.getString(R.string.notif_rec_title), res.getString(text), pausedAtMs = null, chronometer = false)

    /**
     * The ongoing notification: "Recording" with a chronometer that starts [elapsedMs] ago, or "Recording paused"
     * with "Paused at 24:07"; Pause or Resume, and Finish; the content opens the Recording screen.
     */
    fun ongoing(state: RecordingState, elapsedMs: Long): Notification {
        val res = localized()
        val content = content(state)
        val public = NotificationCompat.Builder(res, NotificationChannels.RECORDING)
            .setSmallIcon(UiR.drawable.ui_ic_stat_logo)
            .setContentTitle(content.title)
            .build()
        val builder = NotificationCompat.Builder(res, NotificationChannels.RECORDING)
            .setSmallIcon(UiR.drawable.ui_ic_stat_logo)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openRecording())
        if (content.chronometer) {
            builder.setUsesChronometer(true).setShowWhen(true).setWhen(System.currentTimeMillis() - elapsedMs)
        } else {
            builder.setShowWhen(false)
        }
        content.pausedAtMs?.let { builder.setSubText(res.getString(R.string.notif_rec_paused_at, clock(it))) }
        if (state is RecordingState.Active) {
            if (state.pause == null) {
                builder.addAction(0, res.getString(R.string.notif_action_pause), action(RecordingService.ACTION_PAUSE))
            } else {
                builder.addAction(
                    0,
                    res.getString(R.string.notif_action_resume),
                    action(RecordingService.ACTION_RESUME),
                )
            }
            builder.addAction(0, res.getString(R.string.notif_action_finish), action(RecordingService.ACTION_FINISH))
        }
        return builder.build()
    }

    /** "Recording stopped": free space went below the floor. */
    fun stoppedForSpace(noteId: String) {
        val res = localized()
        post(noteId, result(res, R.string.notif_rec_stopped_title, R.string.notif_rec_stopped_text, noteId))
    }

    /** "Recording stopped": the AAC file reached its size limit. */
    fun stoppedAtFileLimit(noteId: String) {
        val res = localized()
        post(noteId, result(res, R.string.notif_rec_stopped_title, R.string.notif_rec_file_full_text, noteId))
    }

    /** "Microphone stopped working". */
    fun microphoneFailed(noteId: String) {
        val res = localized()
        post(noteId, result(res, R.string.notif_rec_failed_title, R.string.notif_rec_failed_text, noteId))
    }

    private fun warningText(res: Context, state: RecordingState.Active): String? = when {
        state.pause == RecordingPause.OTHER_APP -> res.getString(R.string.notif_rec_other_app)
        else -> when (val warning = state.warning) {
            is RecordingWarning.StorageLow -> res.getString(R.string.notif_rec_storage_low, warning.minutes.toInt())
            null -> null
        }
    }

    private fun result(res: Context, title: Int, text: Int, noteId: String): Notification {
        val public = NotificationCompat.Builder(res, NotificationChannels.RESULTS)
            .setSmallIcon(UiR.drawable.ui_ic_stat_logo)
            .setContentTitle(res.getString(title))
            .build()
        return NotificationCompat.Builder(res, NotificationChannels.RESULTS)
            .setSmallIcon(UiR.drawable.ui_ic_stat_logo)
            .setContentTitle(res.getString(title))
            .setContentText(res.getString(text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(res.getString(text)))
            .setAutoCancel(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(openNote(noteId))
            .build()
    }

    /** One results notification per note, shared with processing (tag = note id). */
    private fun post(noteId: String, notification: Notification) {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) NotificationManagerCompat.from(app).notify(noteId, ProcessingNotifications.RESULT_ID, notification)
    }

    private fun launch(): Intent {
        val launch = app.packageManager.getLaunchIntentForPackage(app.packageName)
        return (launch ?: Intent().setPackage(app.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    private fun openRecording(): PendingIntent {
        val intent = launch().putExtra(ProcessingNotifications.EXTRA_OPEN, OPEN_RECORDING)
        return PendingIntent.getActivity(app, OPEN_RECORDING.hashCode(), intent, FLAGS)
    }

    private fun openNote(noteId: String): PendingIntent {
        val intent = launch()
            .putExtra(ProcessingNotifications.EXTRA_OPEN, ProcessingNotifications.OPEN_NOTE)
            .putExtra(ProcessingNotifications.EXTRA_NOTE_ID, noteId)
        return PendingIntent.getActivity(app, noteId.hashCode(), intent, FLAGS)
    }

    private fun action(action: String): PendingIntent =
        PendingIntent.getService(app, action.hashCode(), RecordingService.intent(app, action), FLAGS)

    companion object {
        /** The ongoing recording notification (processing is 2, results 3). */
        const val RECORDING_ID = 1

        /** The value of [ProcessingNotifications.EXTRA_OPEN] that opens the Recording screen. */
        const val OPEN_RECORDING = "recording"

        private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        /** `mm:ss` or `h:mm:ss`. */
        fun clock(ms: Long): String = ExportClock.clock(ms, ms)
    }
}
