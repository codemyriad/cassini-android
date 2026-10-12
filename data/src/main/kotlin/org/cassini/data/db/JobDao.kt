package org.cassini.data.db

import androidx.annotation.WorkerThread
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import org.cassini.core.library.JobState

/** The persistent transcription queue (docs/architecture.md, sections 3.3 and 5.2). `JobRepository` owns the rules. */
// Room DAOs are flat lists of queries.
@Suppress("TooManyFunctions")
@Dao
abstract class JobDao {
    /** Jobs of live notes in queue order. */
    @Query(
        "SELECT j.*, n.title FROM jobs j JOIN notes n ON n.id = j.note_id WHERE n.trashed_at IS NULL ORDER BY j.seq",
    )
    abstract fun queueFlow(): Flow<List<QueueRow>>

    @Query("SELECT * FROM jobs ORDER BY seq")
    abstract suspend fun all(): List<JobEntity>

    @Query("SELECT * FROM jobs WHERE note_id = :noteId")
    abstract suspend fun job(noteId: String): JobEntity?

    /** The job of a note, for a caller that holds the note's lock. */
    @WorkerThread
    @Query("SELECT * FROM jobs WHERE note_id = :noteId")
    abstract fun jobBlocking(noteId: String): JobEntity?

    @Query("SELECT MAX(seq) FROM jobs")
    abstract suspend fun maxSeq(): Long?

    @Query("SELECT COUNT(*) FROM jobs WHERE state = 'RUNNING'")
    abstract suspend fun runningCount(): Int

    /** The next QUEUED job of a live note in FIFO order. */
    @Query(
        "SELECT j.* FROM jobs j JOIN notes n ON n.id = j.note_id " +
            "WHERE j.state = 'QUEUED' AND n.trashed_at IS NULL ORDER BY j.seq LIMIT 1",
    )
    abstract suspend fun firstQueued(): JobEntity?

    @Insert
    abstract suspend fun insert(job: JobEntity)

    @Insert
    protected abstract fun insertBlocking(job: JobEntity)

    @Query("SELECT MAX(seq) FROM jobs")
    protected abstract fun maxSeqBlocking(): Long?

    /** Inserts [job] at the back of the queue (`seq = max + 1`), in the transaction of a caller that holds a lock. */
    @WorkerThread
    @Transaction
    open fun insertAtBackBlocking(job: JobEntity) {
        insertBlocking(job.copy(seq = (maxSeqBlocking() ?: 0) + 1))
    }

    @Update
    abstract suspend fun update(job: JobEntity)

    @Query("DELETE FROM jobs WHERE note_id = :noteId")
    abstract suspend fun delete(noteId: String)

    /** [delete] inside a transaction that runs under a note lock (adopt). */
    @Query("DELETE FROM jobs WHERE note_id = :noteId")
    abstract fun deleteBlocking(noteId: String)

    @Query("UPDATE jobs SET state = :state, updated_at = :now WHERE note_id = :noteId")
    protected abstract suspend fun setState(noteId: String, state: JobState, now: Long)

    /**
     * Marks the next QUEUED job RUNNING and returns it, in one transaction. Returns null when a RUNNING job exists
     * (there is never more than one) or nothing is queued.
     */
    @Transaction
    open suspend fun claimNext(now: Long): JobEntity? {
        val next = if (runningCount() == 0) firstQueued() else null
        next?.let { setState(it.noteId, JobState.RUNNING, now) }
        return next?.copy(state = JobState.RUNNING, updatedAt = now)
    }
}
