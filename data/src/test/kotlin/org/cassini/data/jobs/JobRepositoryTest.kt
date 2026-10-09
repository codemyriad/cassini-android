package org.cassini.data.jobs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.cassini.core.Failure
import org.cassini.core.format.DocumentState
import org.cassini.core.library.AudioKind
import org.cassini.core.library.JobKind
import org.cassini.core.library.JobState
import org.cassini.core.library.PauseReason
import org.cassini.data.checkpoint.CheckpointRepository
import org.cassini.data.checkpoint.noteId
import org.cassini.data.db.JobEntity
import org.cassini.data.notes.NoteRepository
import org.cassini.data.notes.NotesHarness
import org.cassini.data.notes.SealedAdopter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections

/** The queue rules of JobRepository (architecture 4.7 and 5.2): enqueue, FIFO, one RUNNING job, transitions. */
@RunWith(RobolectricTestRunner::class)
class JobRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var h: NotesHarness
    private lateinit var jobs: JobRepository
    private val cancelled: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Before fun setUp() {
        h = NotesHarness(folder)
        val checkpoints = CheckpointRepository(h.db, h.paths, h.dispatchers, h.locks)
        jobs = JobRepository(
            h.db,
            h.paths,
            h.dispatchers,
            h.locks,
            checkpoints,
            SealedAdopter { h.documents.adoptSealed(it) },
            RunningJobCanceller { cancelled += it },
        ) { h.now }
    }

    @After fun tearDown() = h.close()

    private suspend fun job(id: String): JobEntity = requireNotNull(jobs.job(id)) { "no job $id" }

    private suspend fun notes(count: Int): List<String> = (1..count).map { noteId(it).also { id -> h.plainNote(id) } }

    @Test fun enqueueAddsAtTheBack() = runTest {
        val (a, b) = notes(2)
        assertEquals(1L, (jobs.enqueue(a, JobKind.TRANSCRIBE) as EnqueueOutcome.Queued).job.seq)
        assertEquals(2L, (jobs.enqueue(b, JobKind.TRANSCRIBE) as EnqueueOutcome.Queued).job.seq)
        assertEquals(JobState.QUEUED, job(a).state)
        assertEquals(0, job(a).attempts)
    }

    @Test fun enqueueLeavesQueuedAndRunningJobs() = runTest {
        val (a, b) = notes(2)
        jobs.enqueue(a, JobKind.TRANSCRIBE)
        jobs.enqueue(b, JobKind.TRANSCRIBE)
        assertEquals(EnqueueOutcome.Unchanged(job(a)), jobs.enqueue(a, JobKind.TRANSCRIBE))
        jobs.claimNext()
        assertEquals(EnqueueOutcome.Unchanged(job(a)), jobs.enqueue(a, JobKind.TRANSCRIBE_AGAIN))
        assertEquals(JobState.RUNNING, job(a).state)
        assertEquals(1L, job(a).seq)
    }

    @Test fun enqueueRequeuesStoppedJobsWithAFreshStart() = runTest {
        val ids = notes(4)
        ids.forEach { jobs.enqueue(it, JobKind.TRANSCRIBE) }
        val (paused, failed, consent) = ids
        val other = ids[3]
        h.db.jobs().update(job(paused).copy(state = JobState.PAUSED, pauseReason = PauseReason.MEMORY, attempts = 3))
        h.db.jobs().update(job(failed).copy(state = JobState.FAILED, failure = "DOWNLOAD", attempts = 1))
        h.db.jobs().update(job(consent).copy(state = JobState.NEEDS_CONSENT))

        val seqs = listOf(paused, failed, consent).map { id ->
            val queued = (jobs.enqueue(id, JobKind.TRANSCRIBE_AGAIN) as EnqueueOutcome.Queued).job
            assertEquals(queued, job(id))
            assertEquals(JobState.QUEUED, queued.state)
            assertNull(queued.pauseReason)
            assertNull(queued.failure)
            assertEquals(0, queued.attempts)
            assertEquals(JobKind.TRANSCRIBE_AGAIN, queued.kind)
            queued.seq
        }
        assertEquals(listOf(5L, 6L, 7L), seqs)
        assertEquals(4L, job(other).seq)
    }

    @Test fun enqueueRefusesNotesThatCannotBeTranscribed() = runTest {
        val (trashed, recording, unknown) = notes(3)
        h.notes.moveToTrash(trashed)
        h.db.notes().update(h.row(recording).copy(audioKind = AudioKind.LIVE_CAPTURE))
        h.db.notes().update(
            h.row(unknown).copy(audioKind = AudioKind.DOCUMENT, documentState = DocumentState.UNKNOWN_FORMAT),
        )

        assertEquals(EnqueueOutcome.Refused(EnqueueRefusal.TRASHED), jobs.enqueue(trashed, JobKind.TRANSCRIBE))
        assertEquals(EnqueueOutcome.Refused(EnqueueRefusal.RECORDING), jobs.enqueue(recording, JobKind.TRANSCRIBE))
        assertEquals(
            EnqueueOutcome.Refused(EnqueueRefusal.UNKNOWN_FORMAT),
            jobs.enqueue(unknown, JobKind.TRANSCRIBE_AGAIN),
        )
        assertEquals(EnqueueOutcome.Refused(EnqueueRefusal.NO_NOTE), jobs.enqueue(noteId(9), JobKind.TRANSCRIBE))
        assertTrue(h.db.jobs().all().isEmpty())
    }

    @Test fun claimNextIsFifoWithOneRunningJob() = runTest {
        val ids = notes(3)
        ids.reversed().forEach { jobs.enqueue(it, JobKind.TRANSCRIBE) }

        assertEquals(ids[2], (jobs.claimNext() as ClaimOutcome.Claimed).job.noteId)
        assertEquals(ClaimOutcome.Busy(ids[2]), jobs.claimNext())
        assertFalse(jobs.markRunning(ids[0]))

        jobs.remove(ids[2])
        assertEquals(ids[1], (jobs.claimNext() as ClaimOutcome.Claimed).job.noteId)
        jobs.markPaused(ids[1], PauseReason.USER, 28_000, 60_000, 40)
        assertEquals(ids[0], (jobs.claimNext() as ClaimOutcome.Claimed).job.noteId)
        jobs.markFailed(ids[0], Failure.MODEL)
        assertEquals(ClaimOutcome.Empty, jobs.claimNext())

        // Resume puts the paused note at the back, and it is the next to run.
        jobs.enqueue(ids[1], JobKind.TRANSCRIBE)
        assertEquals(ids[1], (jobs.claimNext() as ClaimOutcome.Claimed).job.noteId)
    }

    @Test fun claimNextSkipsTrashedNotes() = runTest {
        val (a, b) = notes(2)
        jobs.enqueue(a, JobKind.TRANSCRIBE)
        jobs.enqueue(b, JobKind.TRANSCRIBE)
        h.notes.moveToTrash(a)
        assertEquals(b, (jobs.claimNext() as ClaimOutcome.Claimed).job.noteId)
    }

    @Test fun concurrentClaimsRunOneJob() = runTest {
        val ids = notes(8)
        ids.forEach { jobs.enqueue(it, JobKind.TRANSCRIBE) }

        val results = withContext(Dispatchers.IO) { (1..16).map { async { jobs.claimNext() } }.awaitAll() }

        val claimed = results.filterIsInstance<ClaimOutcome.Claimed>()
        assertEquals(1, claimed.size)
        assertEquals(ids[0], claimed.single().job.noteId)
        assertEquals(1, h.db.jobs().all().count { it.state == JobState.RUNNING })
    }

    @Test fun transitionsKeepTheirNumbers() = runTest {
        val (a) = notes(1)
        jobs.enqueue(a, JobKind.TRANSCRIBE)
        jobs.claimNext()
        assertTrue(jobs.recordProgress(a, 28_000, 90_000, 35))
        assertEquals(28_000L to 35, job(a).doneMs to job(a).words)

        assertTrue(jobs.markPaused(a, PauseReason.SYSTEM, 56_000, 90_000, 70))
        job(a).let {
            assertEquals(JobState.PAUSED, it.state)
            assertEquals(PauseReason.SYSTEM, it.pauseReason)
            assertEquals(56_000L, it.doneMs)
            assertEquals(70, it.words)
        }
        assertTrue(jobs.markRunning(a))
        assertTrue(jobs.markFailed(a, JobRepository.CHECKPOINT_MISMATCH))
        assertEquals(JobRepository.CHECKPOINT_MISMATCH, job(a).failure)
        assertNull(job(a).pauseReason)
        assertTrue(jobs.markNeedsConsent(a))
        assertEquals(JobState.NEEDS_CONSENT, job(a).state)
        assertNull(job(a).failure)
        // The service never bumps attempts.
        assertEquals(0, job(a).attempts)

        jobs.remove(a)
        assertNull(jobs.job(a))
        assertFalse(jobs.markPaused(a, PauseReason.USER, 0, 0, 0))
    }

    @Test fun trashPortsRequeueAndCancel() = runTest {
        val (a, b, c) = notes(3)
        jobs.enqueue(a, JobKind.TRANSCRIBE)
        jobs.enqueue(b, JobKind.TRANSCRIBE)

        // Restore of a note whose QUEUED job was kept: it goes to the back.
        jobs.requeueAtBack(a, JobKind.TRANSCRIBE)
        assertEquals(3L, job(a).seq)
        // Restore of a note whose row is missing creates it.
        jobs.requeueAtBack(c, JobKind.TRANSCRIBE_AGAIN)
        assertEquals(JobKind.TRANSCRIBE_AGAIN to 4L, job(c).kind to job(c).seq)

        jobs.cancelAndRemove(a)
        assertTrue(cancelled.isEmpty())
        assertEquals(b, (jobs.claimNext() as ClaimOutcome.Claimed).job.noteId)
        jobs.cancelAndRemove(b)
        assertEquals(listOf(b), cancelled.toList())
        assertNull(jobs.job(b))
    }

    @Test fun restoreThroughNoteRepositoryRequeuesAtTheBack() = runTest {
        val (a, b) = notes(2)
        val notes = NoteRepository(
            h.db,
            h.paths,
            h.dispatchers,
            h.locks,
            h.documents,
            h.background,
            jobs,
            h.ports,
        ) { h.now }
        jobs.enqueue(a, JobKind.TRANSCRIBE)
        jobs.enqueue(b, JobKind.TRANSCRIBE)
        notes.moveToTrash(a)
        notes.restore(a)
        assertEquals(JobState.QUEUED to 3L, job(a).state to job(a).seq)
    }
}
