package org.cassini.data.jobs

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.cassini.core.Failure
import org.cassini.core.format.DocumentState
import org.cassini.core.library.AudioKind
import org.cassini.core.library.JobKind
import org.cassini.core.library.JobState
import org.cassini.core.library.PauseReason
import org.cassini.data.AppPaths
import org.cassini.data.CassiniDispatchers
import org.cassini.data.NoteIds
import org.cassini.data.NoteLocks
import org.cassini.data.checkpoint.CheckpointLoad
import org.cassini.data.checkpoint.CheckpointRepository
import org.cassini.data.checkpoint.WorkFiles
import org.cassini.data.db.CassiniDatabase
import org.cassini.data.db.JobEntity
import org.cassini.data.db.QueueRow
import org.cassini.data.notes.AdoptOutcome
import org.cassini.data.notes.JobQueueWriter
import org.cassini.data.notes.SealedAdopter
import java.io.File

/**
 * The durable transcription queue in Room `jobs` (docs/architecture.md, sections 4.7, 5.2 and 5.3). Only
 * `ProcessingCoordinator` drives it; NoteRepository uses it as the [JobQueueWriter] of Trash.
 *
 * One job at a time, FIFO by `seq`. Every transition is one Room transaction. `attempts` counts automatic restarts
 * since the last user action: [reconcileOnStart] is the only place that raises it, and a user's [enqueue] resets it.
 */
// Manual DI: the repositories take their collaborators explicitly; one method per queue transition.
@Suppress("LongParameterList", "TooManyFunctions")
class JobRepository(
    private val database: CassiniDatabase,
    private val paths: AppPaths,
    private val dispatchers: CassiniDispatchers,
    private val locks: NoteLocks,
    private val checkpoints: CheckpointRepository,
    /** Finishes an interrupted adopt: the app passes `adoptSealed` then `VoiceRepository.adoptPending`. */
    private val adopter: SealedAdopter,
    private val canceller: RunningJobCanceller = RunningJobCanceller {},
    private val clock: () -> Long = System::currentTimeMillis,
) : JobQueueWriter {
    private val jobs = database.jobs()
    private val notes = database.notes()

    /** Jobs of live notes in queue order. */
    fun queue(): Flow<List<QueueRow>> = jobs.queueFlow()

    suspend fun job(noteId: String): JobEntity? = jobs.job(noteId)

    /**
     * Puts the note's job in the queue (architecture 5.2): a QUEUED or RUNNING job is left as it is; a PAUSED, FAILED
     * or NEEDS_CONSENT job, or a note without a job, goes to the back of the queue with the failure and pause reason
     * cleared and attempts 0. A recording note, a trashed note and an UNKNOWN_FORMAT document are refused.
     */
    suspend fun enqueue(noteId: String, kind: JobKind): EnqueueOutcome = database.withTransaction {
        val note = notes.note(noteId)
        val existing = jobs.job(noteId)
        when {
            note == null -> EnqueueOutcome.Refused(EnqueueRefusal.NO_NOTE)
            note.trashedAt != null -> EnqueueOutcome.Refused(EnqueueRefusal.TRASHED)
            note.audioKind == AudioKind.LIVE_CAPTURE -> EnqueueOutcome.Refused(EnqueueRefusal.RECORDING)
            note.documentState == DocumentState.UNKNOWN_FORMAT ->
                EnqueueOutcome.Refused(EnqueueRefusal.UNKNOWN_FORMAT)
            existing != null && (existing.state == JobState.QUEUED || existing.state == JobState.RUNNING) ->
                EnqueueOutcome.Unchanged(existing)
            else -> EnqueueOutcome.Queued(putAtBack(noteId, kind, existing))
        }
    }

    /**
     * Restore of a note that was QUEUED when it was trashed: back to the queue at the back, creating the row when it
     * is missing. A RUNNING job is left alone.
     */
    override suspend fun requeueAtBack(noteId: String, kind: JobKind) {
        database.withTransaction {
            val existing = jobs.job(noteId)
            if (existing?.state != JobState.RUNNING && notes.note(noteId) != null) putAtBack(noteId, kind, existing)
        }
    }

    /** Step 1 of the purge (architecture 4.10): stops the job if it runs, then removes its row. */
    override suspend fun cancelAndRemove(noteId: String) {
        if (jobs.job(noteId)?.state == JobState.RUNNING) canceller.cancelAndJoin(noteId)
        jobs.delete(noteId)
    }

    /**
     * Marks the first QUEUED job of a live note RUNNING, in one transaction. Fails ([ClaimOutcome.Busy]) when a
     * RUNNING row exists, so at most one job runs.
     */
    suspend fun claimNext(): ClaimOutcome = database.withTransaction {
        val running = jobs.all().firstOrNull { it.state == JobState.RUNNING }
        val claimed = if (running == null) jobs.claimNext(clock()) else null
        when {
            running != null -> ClaimOutcome.Busy(running.noteId)
            claimed != null -> ClaimOutcome.Claimed(claimed)
            else -> ClaimOutcome.Empty
        }
    }

    /** Marks the job of [noteId] RUNNING unless another job runs. Returns whether it is RUNNING now. */
    suspend fun markRunning(noteId: String): Boolean = database.withTransaction {
        val job = jobs.job(noteId)
        val other = jobs.all().any { it.state == JobState.RUNNING && it.noteId != noteId }
        if (job != null && !other) jobs.update(job.copy(state = JobState.RUNNING, updatedAt = clock()))
        job != null && !other
    }

    /** The job stopped with its checkpoint saved at [doneMs] of [totalMs] with [words] words. */
    suspend fun markPaused(noteId: String, reason: PauseReason, doneMs: Long, totalMs: Long, words: Int): Boolean =
        change(noteId) {
            it.copy(
                state = JobState.PAUSED,
                pauseReason = reason,
                failure = null,
                doneMs = doneMs,
                totalMs = totalMs,
                words = words,
            )
        }

    /** The job failed with [failure]: a core [Failure] name, a download error name or `CHECKPOINT_MISMATCH`. */
    suspend fun markFailed(noteId: String, failure: String): Boolean =
        change(noteId) { it.copy(state = JobState.FAILED, failure = failure, pauseReason = null) }

    suspend fun markFailed(noteId: String, failure: Failure): Boolean = markFailed(noteId, failure.name)

    /** The job needs the model download consent first ("Ready to transcribe"). */
    suspend fun markNeedsConsent(noteId: String): Boolean =
        change(noteId) { it.copy(state = JobState.NEEDS_CONSENT, pauseReason = null, failure = null) }

    /** The numbers of the last checkpoint, for the Library and the queue. */
    suspend fun recordProgress(noteId: String, doneMs: Long, totalMs: Long, words: Int): Boolean =
        change(noteId) { it.copy(doneMs = doneMs, totalMs = totalMs, words = words) }

    /** Removes the job of [noteId] (it ended, or the user removed it from the queue). */
    suspend fun remove(noteId: String) = jobs.delete(noteId)

    /**
     * Repairs the queue after a process start (architecture 5.3), before anything else uses it. [activeNoteId] is the
     * note being recorded now, if any; it is left alone.
     *
     * - A `work/sealed` marker: the adopt is finished through [adopter] and the job removed. If the sealed output is
     *   unreadable (the adopter deleted it with its marker), the next rules apply.
     * - RUNNING without a checkpoint: QUEUED with its `seq`, attempts + 1.
     * - RUNNING with a valid checkpoint: QUEUED with its `seq`, attempts + 1; above [MAX_AUTOMATIC_ATTEMPTS] it is
     *   PAUSED(INTERRUPTED) instead. The same limit applies without a checkpoint, so a crash before the first
     *   checkpoint (model load, first window) cannot restart forever ("at most 2 automatic attempts", section 5.3).
     * - PAUSED without a checkpoint: stays PAUSED with `done_ms` 0.
     * - RUNNING or PAUSED whose checkpoint fails validation: FAILED(CHECKPOINT_MISMATCH).
     * - A `work/` directory without a job row and without a marker: deleted.
     */
    suspend fun reconcileOnStart(activeNoteId: String?): ReconcileReport {
        val rows = jobs.all().associateBy { it.noteId }
        val workDirs = withContext(dispatchers.io) {
            paths.notes.listFiles().orEmpty()
                .filter { NoteIds.isValid(it.name) && paths.work(it.name).isDirectory }
                .map { it.name }
        }
        val report = Report()
        (rows.keys + workDirs).toSortedSet().filter { it != activeNoteId }.forEach { reconcile(it, rows[it], report) }
        return report.build()
    }

    private suspend fun reconcile(noteId: String, row: JobEntity?, report: Report) {
        if (checkpoints.sealedMarker(noteId)) {
            when (adopter.adopt(noteId)) {
                is AdoptOutcome.Adopted -> {
                    jobs.delete(noteId)
                    checkpoints.release(noteId)
                    report.adopted += noteId
                    return
                }
                // Unreadable: the output and its marker are gone; NothingToAdopt: a race with a finished adopt.
                AdoptOutcome.Unreadable, AdoptOutcome.NothingToAdopt, AdoptOutcome.NoNote -> Unit
            }
        }
        val job = row?.let { jobs.job(noteId) }
        if (job == null) {
            deleteOrphanWork(noteId, report)
            return
        }
        when (job.state) {
            JobState.RUNNING -> reconcileRunning(job, checkpoints.load(noteId), report)
            JobState.PAUSED -> reconcilePaused(job, checkpoints.load(noteId), report)
            JobState.QUEUED, JobState.FAILED, JobState.NEEDS_CONSENT -> Unit
        }
    }

    private suspend fun reconcileRunning(job: JobEntity, load: CheckpointLoad, report: Report) {
        val attempts = job.attempts + 1
        val now = clock()
        when {
            load is CheckpointLoad.Mismatch -> {
                jobs.update(mismatch(job, now))
                report.mismatched += job.noteId
            }
            attempts > MAX_AUTOMATIC_ATTEMPTS -> {
                jobs.update(
                    job.copy(
                        state = JobState.PAUSED,
                        pauseReason = PauseReason.INTERRUPTED,
                        attempts = attempts,
                        updatedAt = now,
                    ).withCheckpoint(load),
                )
                report.interrupted += job.noteId
            }
            else -> {
                val queued = job.copy(state = JobState.QUEUED, attempts = attempts, updatedAt = now)
                jobs.update(queued.withCheckpoint(load))
                report.requeued += job.noteId
            }
        }
    }

    private suspend fun reconcilePaused(job: JobEntity, load: CheckpointLoad, report: Report) {
        when (load) {
            is CheckpointLoad.Mismatch -> {
                jobs.update(mismatch(job, clock()))
                report.mismatched += job.noteId
            }
            CheckpointLoad.Missing -> if (job.doneMs != 0L || job.words != 0) {
                jobs.update(job.copy(doneMs = 0, words = 0, updatedAt = clock()))
                report.restarted += job.noteId
            }
            is CheckpointLoad.Valid -> Unit
        }
    }

    private suspend fun deleteOrphanWork(noteId: String, report: Report) {
        val deleted = locks.withNoteLock(noteId, dispatchers.io) {
            val work = paths.work(noteId)
            // A marker left by an adopt that could not run (no row) is kept for the catalogue rebuild.
            val orphan = work.isDirectory && jobs.jobBlocking(noteId) == null &&
                !File(work, WorkFiles.SEALED_MARKER).isFile
            orphan && work.deleteRecursively()
        }
        if (deleted) {
            checkpoints.release(noteId)
            report.deletedWork += noteId
        }
    }

    private fun mismatch(job: JobEntity, now: Long) = job.copy(
        state = JobState.FAILED,
        failure = CHECKPOINT_MISMATCH,
        pauseReason = null,
        updatedAt = now,
    )

    private fun JobEntity.withCheckpoint(load: CheckpointLoad): JobEntity = when (load) {
        is CheckpointLoad.Valid -> copy(
            doneMs = load.state.doneMs,
            totalMs = load.state.durationMs,
            words = load.state.recognition.chunks.words.size,
        )
        else -> copy(doneMs = 0, words = 0)
    }

    /** Back of the queue: QUEUED, `seq = max + 1`, failure and pause cleared, attempts 0. In a transaction. */
    private suspend fun putAtBack(noteId: String, kind: JobKind, existing: JobEntity?): JobEntity {
        val now = clock()
        val seq = (jobs.maxSeq() ?: 0) + 1
        val job = existing?.copy(
            seq = seq,
            kind = kind,
            state = JobState.QUEUED,
            pauseReason = null,
            failure = null,
            attempts = 0,
            updatedAt = now,
        ) ?: JobEntity(
            noteId = noteId,
            seq = seq,
            kind = kind,
            state = JobState.QUEUED,
            enqueuedAt = now,
            updatedAt = now,
        )
        if (existing == null) jobs.insert(job) else jobs.update(job)
        return job
    }

    private suspend fun change(noteId: String, update: (JobEntity) -> JobEntity): Boolean = database.withTransaction {
        val job = jobs.job(noteId)
        job?.let { jobs.update(update(it).copy(updatedAt = clock())) }
        job != null
    }

    private class Report {
        val adopted = mutableListOf<String>()
        val requeued = mutableListOf<String>()
        val interrupted = mutableListOf<String>()
        val restarted = mutableListOf<String>()
        val mismatched = mutableListOf<String>()
        val deletedWork = mutableListOf<String>()

        fun build() = ReconcileReport(adopted, requeued, interrupted, restarted, mismatched, deletedWork)
    }

    companion object {
        /** The failure of a job whose checkpoint no longer validates: "Saved progress no longer matches". */
        const val CHECKPOINT_MISMATCH = "CHECKPOINT_MISMATCH"

        /** Automatic restarts after process death before a job waits for Resume (architecture 5.3). */
        const val MAX_AUTOMATIC_ATTEMPTS = 2
    }
}
