package org.cassini.android.processing

import org.cassini.core.library.JobState
import org.cassini.data.db.JobEntity
import org.cassini.data.settings.QueueHold
import org.cassini.engine.job.JobPhase

/**
 * What the foreground service must be while the coordinator works (architecture 5.7). [NONE]: nothing to do, the
 * service may stop. [DATA_SYNC]: downloads, the start of a job, and inference on API 29-34. [MEDIA_PROCESSING]:
 * inference on API 35+. Below API 29 the service type is ignored (`ServiceCompat` starts it untyped).
 */
enum class ForegroundNeed { NONE, DATA_SYNC, MEDIA_PROCESSING }

/** The job that runs now: [noteId] with its [title] and what its tracker shows ([phase]). */
data class RunningJob(val noteId: String, val title: String, val phase: JobPhase)

/** One QUEUED note in queue order. */
data class QueuedNote(val noteId: String, val title: String)

/**
 * The job state of one note, as the Note screen and the Library show it: the row as Room has it, [ahead] (the number
 * of QUEUED notes before a QUEUED note; 0 otherwise) and [phase] while its job runs in this process.
 */
data class NoteJobState(val job: JobEntity, val ahead: Int, val phase: JobPhase?) {
    val state: JobState get() = job.state
}

/**
 * Everything the coordinator knows, as one immutable value (architecture 5.1).
 *
 * [loading] is true until the startup work ended and the queue was read once. [running] is the job of this process,
 * [queue] the QUEUED notes in FIFO order, [hold] the queue hold, [notes] the job of every live note that has one,
 * [downloading] a standalone model download (no note), [draining] the drain loop runs, and [serviceForeground] the
 * processing service is in the foreground now (the recording hand-off waits for it).
 */
data class ProcessingState(
    val loading: Boolean = true,
    val running: RunningJob? = null,
    val queue: List<QueuedNote> = emptyList(),
    val hold: QueueHold? = null,
    val notes: Map<String, NoteJobState> = emptyMap(),
    val install: InstallState = InstallState.Idle,
    val downloading: Boolean = false,
    val draining: Boolean = false,
    val serviceForeground: Boolean = false,
) {
    /** The QUEUED notes after the running one: the "1 more in queue" sub-text. */
    val waiting: Int get() = queue.size

    companion object {
        /** API 35: the first level with the `mediaProcessing` service type (and its 6 h per 24 h budget). */
        const val MEDIA_PROCESSING_API = 35

        /** The service type that [state] needs on a device with [sdkInt]. */
        fun foregroundNeed(state: ProcessingState, sdkInt: Int): ForegroundNeed {
            val phase = state.running?.phase
            return when {
                phase is JobPhase.Transcribing || phase == JobPhase.Stopping ->
                    if (sdkInt >= MEDIA_PROCESSING_API) ForegroundNeed.MEDIA_PROCESSING else ForegroundNeed.DATA_SYNC
                phase != null || state.draining || state.downloading -> ForegroundNeed.DATA_SYNC
                else -> ForegroundNeed.NONE
            }
        }
    }
}
