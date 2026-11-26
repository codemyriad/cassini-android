package io.codemyriad.cassini.recorder

import android.content.Context
import android.text.format.DateUtils
import io.codemyriad.cassini.R
import io.codemyriad.cassini.data.recorder.LastSync
import io.codemyriad.cassini.data.recorder.RecorderSettings
import io.codemyriad.cassini.ui.library.RecorderBannerUi

/** The text of the recorder sync states (the Recorder screen and the notification). */
object RecorderTexts {
    private const val PERCENT = 100

    fun activity(context: Context, activity: RecorderActivity): String = when (activity) {
        RecorderActivity.Idle -> ""
        RecorderActivity.Connecting -> context.getString(R.string.recorder_activity_connecting)
        is RecorderActivity.Syncing -> RecorderTextHelpers.syncing(context, activity)
        is RecorderActivity.Binding -> when (activity.step) {
            BindStep.BONDING -> context.getString(R.string.recorder_activity_bonding)
            BindStep.CONNECTING -> context.getString(R.string.recorder_activity_connecting)
            BindStep.PIN -> context.getString(R.string.recorder_bind_pin)
            BindStep.PAIRING -> context.getString(R.string.recorder_activity_pairing)
        }
    }

    /** "Last sync <time>: <how it ended>", or "No sync yet." */
    fun lastSync(context: Context, last: LastSync?): String {
        if (last == null) return context.getString(R.string.recorder_last_sync_never)
        val time = DateUtils.formatDateTime(
            context,
            last.at,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
        )
        val outcome = when (last.outcome) {
            RecorderSync.OUTCOME_OK -> RecorderTextHelpers.ok(context, last)
            RecorderSync.OUTCOME_UNREACHABLE -> context.getString(R.string.recorder_outcome_unreachable)
            RecorderSync.OUTCOME_REBIND -> context.getString(R.string.recorder_outcome_rebind)
            RecorderSync.OUTCOME_FAILED -> context.getString(R.string.recorder_outcome_failed, last.detail.orEmpty())
            else -> context.getString(R.string.recorder_outcome_unavailable)
        }
        return context.getString(R.string.recorder_last_sync, time, outcome)
    }

    /** The download speed of [last] in KB/s (1000 bytes), or null when it downloaded nothing. */
    fun speedKilobytesPerSecond(last: LastSync): Long? =
        if (last.downloadedBytes > 0 && last.downloadMillis > 0) last.downloadedBytes / last.downloadMillis else null

    fun status(context: Context, row: RecordingRow): String = when (row.status) {
        RecordingStatus.IMPORT_FAILED ->
            context.getString(R.string.recorder_status_import_failed, row.failure.orEmpty())
        RecordingStatus.NOTE_GONE -> context.getString(R.string.recorder_status_note_gone)
        RecordingStatus.WAITING_FOR_CONSENT -> context.getString(R.string.recorder_status_consent)
        RecordingStatus.TRANSCRIBING -> context.getString(R.string.recorder_status_transcribing)
        RecordingStatus.TRANSCRIPTION_FAILED -> context.getString(R.string.recorder_status_transcription_failed)
        RecordingStatus.IMPORTED -> context.getString(R.string.recorder_status_imported)
        RecordingStatus.TRANSCRIBED -> context.getString(R.string.recorder_status_transcribed)
        RecordingStatus.TRANSCRIBED_NO_SPEECH -> context.getString(R.string.recorder_status_no_speech)
        RecordingStatus.TRANSCRIBED_NOT_ON_RECORDER -> context.getString(R.string.recorder_status_not_on_recorder)
        RecordingStatus.UPLOADED -> context.getString(R.string.recorder_status_uploaded)
        RecordingStatus.UPLOADED_NO_SPEECH -> context.getString(R.string.recorder_status_uploaded_no_speech)
        RecordingStatus.UPLOAD_FAILED ->
            context.getString(R.string.recorder_status_upload_failed, row.failure.orEmpty())
    }

    fun queue(context: Context, queue: RecorderQueueCounts): String? {
        val detail = RecorderTextHelpers.queueParts(context, queue) ?: return null
        return context.getString(R.string.recorder_queue_summary, detail)
    }

    fun settingsSubtitle(
        context: Context,
        settings: RecorderSettings?,
        activity: RecorderActivity,
        notes: Map<String, NoteSyncInfo>,
    ): String {
        val device = settings?.binding?.device ?: return context.getString(R.string.recorder_not_bound)
        val live = activity(context, activity).ifEmpty {
            val rows = RecorderRows.rows(settings.current?.recordings.orEmpty(), notes)
            RecorderTextHelpers.queueParts(context, RecorderRows.queue(rows))
        }
        return if (live.isNullOrEmpty()) {
            device.name
        } else {
            context.getString(R.string.recorder_settings_subtitle_status, device.name, live)
        }
    }

    fun libraryBanner(
        context: Context,
        settings: RecorderSettings?,
        activity: RecorderActivity,
        notes: Map<String, NoteSyncInfo>,
    ): RecorderBannerUi? = RecorderTextHelpers.libraryBanner(context, settings, activity, notes)

    fun bindFailure(context: Context, failure: BindFailure): String = when (failure) {
        is BindFailure.NotBonded -> context.getString(R.string.recorder_bind_failed_not_bonded)
        is BindFailure.Unreachable -> context.getString(R.string.recorder_bind_failed_unreachable)
        is BindFailure.StaleBond -> context.getString(R.string.recorder_bind_failed_stale_bond)
        is BindFailure.PairingClosed -> context.getString(R.string.recorder_bind_failed_closed)
        is BindFailure.Chooser -> context.getString(R.string.recorder_bind_failed_chooser, failure.reason.orEmpty())
        is BindFailure.Unavailable -> readiness(context, failure.readiness)
        is BindFailure.Other -> context.getString(R.string.recorder_bind_failed_other, failure.reason.orEmpty())
    }

    fun readiness(context: Context, readiness: RadioReadiness): String = when (readiness) {
        RadioReadiness.READY -> ""
        RadioReadiness.NO_BLUETOOTH -> context.getString(R.string.recorder_no_bluetooth)
        RadioReadiness.NO_PERMISSION -> context.getString(R.string.recorder_permission)
        RadioReadiness.OFF -> context.getString(R.string.recorder_bluetooth_off)
    }
}

private object RecorderTextHelpers {
    private const val PERCENT = 100

    fun syncing(context: Context, activity: RecorderActivity.Syncing): String = when (activity.step) {
        SyncStep.CHECKING -> context.getString(R.string.recorder_activity_checking)
        SyncStep.DOWNLOADING -> {
            val percent = if (activity.total > 0) (activity.done * PERCENT / activity.total).toInt() else 0
            context.getString(R.string.recorder_activity_downloading, activity.file.orEmpty(), percent)
        }
        SyncStep.IMPORTING -> context.getString(R.string.recorder_activity_importing, activity.file.orEmpty())
        SyncStep.UPLOADING -> context.getString(R.string.recorder_activity_uploading, activity.file.orEmpty())
    }

    fun ok(context: Context, last: LastSync): String {
        val speed = RecorderTexts.speedKilobytesPerSecond(last)
        return if (speed == null) {
            context.getString(R.string.recorder_outcome_ok, last.downloaded, last.uploaded)
        } else {
            context.getString(R.string.recorder_outcome_ok_speed, last.downloaded, last.uploaded, speed)
        }
    }

    fun libraryBanner(
        context: Context,
        settings: RecorderSettings?,
        activity: RecorderActivity,
        notes: Map<String, NoteSyncInfo>,
    ): RecorderBannerUi? {
        val device = settings?.binding?.device ?: return null
        val rows = RecorderRows.rows(settings.current?.recordings.orEmpty(), notes)
        val queue = RecorderRows.queue(rows)
        val openLabel = context.getString(R.string.recorder_action_open)
        return when (activity) {
            is RecorderActivity.Binding -> null
            RecorderActivity.Idle -> idleBanner(context, settings, device.name, queue, openLabel)
            RecorderActivity.Connecting,
            is RecorderActivity.Syncing -> activeBanner(context, device.name, activity, queue, openLabel)
        }
    }

    private fun idleBanner(
        context: Context,
        settings: RecorderSettings,
        deviceName: String,
        queue: RecorderQueueCounts,
        clickLabel: String,
    ): RecorderBannerUi? {
        if (settings.current?.rebindNeeded == true || queue.waitingForRecorder == 0) return null
        val line = if (queue.transcribing > 0) {
            val tx = context.resources.getQuantityString(
                R.plurals.recorder_queue_transcribing,
                queue.transcribing,
                queue.transcribing,
            )
            context.getString(R.string.recorder_banner_waiting_with_tx, deviceName, tx)
        } else {
            context.getString(R.string.recorder_banner_waiting_line, deviceName)
        }
        return RecorderBannerUi(
            title = context.resources.getQuantityString(
                R.plurals.recorder_banner_waiting_title,
                queue.waitingForRecorder,
                queue.waitingForRecorder,
            ),
            line = line,
            clickLabel = clickLabel,
        )
    }

    private fun activeBanner(
        context: Context,
        deviceName: String,
        activity: RecorderActivity,
        queue: RecorderQueueCounts,
        clickLabel: String,
    ): RecorderBannerUi {
        val syncing = activity as? RecorderActivity.Syncing
        val queueSummary = queueParts(context, queue)
        val line = if (queueSummary != null) "$deviceName · $queueSummary" else deviceName
        if (syncing?.step == SyncStep.DOWNLOADING) {
            val total = syncing.total
            val percent = if (total > 0) (syncing.done * PERCENT / total).toInt().coerceIn(0, PERCENT) else null
            val progress = if (total > 0) (syncing.done.toFloat() / total.toFloat()).coerceIn(0f, 1f) else null
            return RecorderBannerUi(
                title = context.getString(R.string.recorder_banner_downloading_title, syncing.file.orEmpty()),
                percent = percent,
                progress = progress,
                line = line,
                clickLabel = clickLabel,
            )
        }
        return RecorderBannerUi(
            title = RecorderTexts.activity(context, activity),
            line = line,
            clickLabel = clickLabel,
        )
    }

    fun queueParts(context: Context, queue: RecorderQueueCounts): String? {
        val parts = buildList {
            if (queue.transcribing > 0) {
                add(
                    context.resources.getQuantityString(
                        R.plurals.recorder_queue_transcribing,
                        queue.transcribing,
                        queue.transcribing,
                    ),
                )
            }
            if (queue.waitingForRecorder > 0) {
                add(
                    context.resources.getQuantityString(
                        R.plurals.recorder_queue_waiting,
                        queue.waitingForRecorder,
                        queue.waitingForRecorder,
                    ),
                )
            }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }
}
