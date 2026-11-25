package io.codemyriad.cassini.recorder

import android.content.Context
import android.text.format.DateUtils
import io.codemyriad.cassini.R
import io.codemyriad.cassini.data.recorder.LastSync

/** The text of the recorder sync states (the Recorder screen and the notification). */
object RecorderTexts {
    private const val PERCENT = 100

    fun activity(context: Context, activity: RecorderActivity): String = when (activity) {
        RecorderActivity.Idle -> ""
        RecorderActivity.Connecting -> context.getString(R.string.recorder_activity_connecting)
        is RecorderActivity.Syncing -> syncing(context, activity)
        is RecorderActivity.Binding -> when (activity.step) {
            BindStep.BONDING -> context.getString(R.string.recorder_activity_bonding)
            BindStep.CONNECTING -> context.getString(R.string.recorder_activity_connecting)
            BindStep.PIN -> context.getString(R.string.recorder_bind_pin)
            BindStep.PAIRING -> context.getString(R.string.recorder_activity_pairing)
        }
    }

    private fun syncing(context: Context, activity: RecorderActivity.Syncing): String = when (activity.step) {
        SyncStep.CHECKING -> context.getString(R.string.recorder_activity_checking)
        SyncStep.DOWNLOADING -> {
            val percent = if (activity.total > 0) (activity.done * PERCENT / activity.total).toInt() else 0
            context.getString(R.string.recorder_activity_downloading, activity.file.orEmpty(), percent)
        }
        SyncStep.IMPORTING -> context.getString(R.string.recorder_activity_importing, activity.file.orEmpty())
        SyncStep.UPLOADING -> context.getString(R.string.recorder_activity_uploading, activity.file.orEmpty())
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
            RecorderSync.OUTCOME_OK -> context.getString(R.string.recorder_outcome_ok, last.downloaded, last.uploaded)
            RecorderSync.OUTCOME_UNREACHABLE -> context.getString(R.string.recorder_outcome_unreachable)
            RecorderSync.OUTCOME_REBIND -> context.getString(R.string.recorder_outcome_rebind)
            RecorderSync.OUTCOME_FAILED -> context.getString(R.string.recorder_outcome_failed, last.detail.orEmpty())
            else -> context.getString(R.string.recorder_outcome_unavailable)
        }
        return context.getString(R.string.recorder_last_sync, time, outcome)
    }

    fun status(context: Context, row: RecordingRow): String = when (row.status) {
        RecordingStatus.IMPORT_FAILED ->
            context.getString(R.string.recorder_status_import_failed, row.failure.orEmpty())
        RecordingStatus.NOTE_GONE -> context.getString(R.string.recorder_status_note_gone)
        RecordingStatus.WAITING_FOR_CONSENT -> context.getString(R.string.recorder_status_consent)
        RecordingStatus.TRANSCRIBING -> context.getString(R.string.recorder_status_transcribing)
        RecordingStatus.TRANSCRIPTION_FAILED -> context.getString(R.string.recorder_status_transcription_failed)
        RecordingStatus.IMPORTED -> context.getString(R.string.recorder_status_imported)
        RecordingStatus.TRANSCRIBED -> context.getString(R.string.recorder_status_transcribed)
        RecordingStatus.UPLOADED -> context.getString(R.string.recorder_status_uploaded)
        RecordingStatus.UPLOAD_FAILED ->
            context.getString(R.string.recorder_status_upload_failed, row.failure.orEmpty())
    }

    fun bindFailure(context: Context, failure: BindFailure): String = when (failure) {
        is BindFailure.NotBonded -> context.getString(R.string.recorder_bind_failed_not_bonded)
        is BindFailure.Unreachable -> context.getString(R.string.recorder_bind_failed_unreachable)
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
