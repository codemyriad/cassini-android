package org.cassini.android.recording

import org.cassini.core.audio.RecordingPolicy
import org.cassini.core.audio.RecordingSpace

/** The capture format: `capture.opus` (platform Opus, API 29+) or `capture.m4a` (AAC). Both record at 48 kHz. */
enum class CaptureFormat(val extension: String, val sampleRateHz: Int, val nominalBytesPerSecond: Long) {
    OPUS("opus", SAMPLE_RATE_HZ, RecordingPolicy.OPUS_BYTES_PER_SECOND),
    M4A("m4a", SAMPLE_RATE_HZ, RecordingPolicy.AAC_BYTES_PER_SECOND),
    ;

    companion object {
        fun of(extension: String): CaptureFormat = if (extension == OPUS.extension) OPUS else M4A
    }
}

private const val SAMPLE_RATE_HZ = 48_000

/** Why a recording is paused (architecture 5.8). */
enum class RecordingPause {
    /** The user pressed Pause. */
    USER,

    /** Another app took the microphone (a call). The recording stays paused until the user resumes. */
    OTHER_APP,
}

/** The warning of a running recording (features.md 13.2). */
sealed interface RecordingWarning {
    /** About [minutes] of space left (rounded as the hint rounds). */
    data class StorageLow(val minutes: Long) : RecordingWarning

    /** The 2 h cap stops the recording in [msLeft]. */
    data class NearTimeLimit(val msLeft: Long) : RecordingWarning
}

/** What the app does after a saved recording (features.md section 2, Finish flow). */
enum class AfterFinish {
    /** Consent exists: the note is QUEUED and the processing service was asked to start. */
    TRANSCRIBE,

    /** No consent and no decline: the Note screen opens with the Model download screen on top. */
    ASK_CONSENT,

    /** The user declined the download, or the note could not be queued: "Audio only". */
    AUDIO_ONLY,
}

/** How a recording ended. */
sealed interface RecordingOutcome {
    /** Finish, or the stop at the 2 h cap ([atTimeLimit]). */
    data class Saved(val next: AfterFinish, val atTimeLimit: Boolean = false) : RecordingOutcome

    /** Free space went below the floor: the audio so far is saved and nothing is queued. */
    data object StoppedForSpace : RecordingOutcome

    /** The capture failed: the audio so far is saved (when there is any) and nothing is queued. */
    data object MicrophoneFailed : RecordingOutcome

    /** The service or the microphone did not start: the row and the directory were removed. */
    data object CouldNotStart : RecordingOutcome
}

/** The state of the one recording of the process (architecture 5.8). */
sealed interface RecordingState {
    data object Idle : RecordingState

    /** [start][RecordingController.start] was called; the service and the microphone are starting. */
    data class Starting(val title: String) : RecordingState

    /**
     * The microphone records into [noteId]'s capture file. [title] is what the user typed (written to the row 500 ms
     * after the last change); [elapsedMs] and [space] change once a second.
     */
    data class Active(
        val noteId: String,
        val title: String,
        val format: CaptureFormat,
        val sampleRateHz: Int,
        val elapsedMs: Long,
        val pause: RecordingPause?,
        val space: RecordingSpace,
        val warning: RecordingWarning?,
    ) : RecordingState

    /** The capture stops and the note is saved; the hand-off to processing may still run. */
    data class Finishing(val noteId: String, val title: String, val elapsedMs: Long) : RecordingState

    /** The recording ended. [noteId] is null when no note was kept (it could not start, or nothing was captured). */
    data class Ended(val noteId: String?, val outcome: RecordingOutcome) : RecordingState
}

/** Starting, Active or Finishing: a microphone service is (or must be) in the foreground. */
val RecordingState.inProgress: Boolean
    get() = this is RecordingState.Starting || this is RecordingState.Active || this is RecordingState.Finishing
