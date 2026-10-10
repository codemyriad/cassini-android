package org.cassini.data.jobs

import org.cassini.data.db.JobEntity

// The results of the queue operations (docs/architecture.md, sections 4.7, 5.2 and 5.3).

/** The result of `JobRepository.enqueue`. */
sealed interface EnqueueOutcome {
    /** The job is QUEUED at the back of the queue. */
    data class Queued(val job: JobEntity) : EnqueueOutcome

    /** The job was already QUEUED or RUNNING; nothing changed. */
    data class Unchanged(val job: JobEntity) : EnqueueOutcome

    /** The note cannot be transcribed now (architecture 5.2 rule 3). */
    data class Refused(val reason: EnqueueRefusal) : EnqueueOutcome
}

/** Why a note cannot be enqueued. */
enum class EnqueueRefusal {
    NO_NOTE,

    /** The note is in Trash. */
    TRASHED,

    /** The note is still recording (`LIVE_CAPTURE`). */
    RECORDING,

    /** The note's document has a format version this build cannot read. */
    UNKNOWN_FORMAT,

    /** The output of the last transcription is not fully saved yet (an I/O error); try again later. */
    ADOPT_PENDING,
}

/** The result of `JobRepository.claimNext`. */
sealed interface ClaimOutcome {
    /** [job] is RUNNING now. */
    data class Claimed(val job: JobEntity) : ClaimOutcome

    /** Another job is RUNNING; there is never more than one. */
    data class Busy(val runningNoteId: String) : ClaimOutcome

    /** No live note has a QUEUED job. */
    data object Empty : ClaimOutcome
}

/** Stops a running job before its row is removed; the coordinator implements it (P6) with `Job.cancel()`+`join()`. */
fun interface RunningJobCanceller {
    suspend fun cancelAndJoin(noteId: String)
}

/** What `JobRepository.reconcileOnStart` did, per architecture 5.3 row; every list holds note ids. */
data class ReconcileReport(
    /** A sealed output was installed and the job removed. */
    val adopted: List<String> = emptyList(),
    /** A RUNNING row became QUEUED again. */
    val requeued: List<String> = emptyList(),
    /** A RUNNING row passed the automatic attempts and became PAUSED(INTERRUPTED). */
    val interrupted: List<String> = emptyList(),
    /** A PAUSED row without a checkpoint starts again from the beginning (`done_ms` = 0). */
    val restarted: List<String> = emptyList(),
    /** The checkpoint fails validation: FAILED(CHECKPOINT_MISMATCH). */
    val mismatched: List<String> = emptyList(),
    /** A `work/` directory without a job row and without a marker was deleted (never one of a note in Trash). */
    val deletedWork: List<String> = emptyList(),
)
