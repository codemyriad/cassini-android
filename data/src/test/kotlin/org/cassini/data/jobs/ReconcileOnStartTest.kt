package org.cassini.data.jobs

import kotlinx.coroutines.test.runTest
import org.cassini.core.library.AudioKind
import org.cassini.core.library.JobKind
import org.cassini.core.library.JobState
import org.cassini.core.library.PauseReason
import org.cassini.data.checkpoint.CheckpointLoad
import org.cassini.data.checkpoint.CheckpointRepository
import org.cassini.data.checkpoint.checkpoint
import org.cassini.data.checkpoint.noteId
import org.cassini.data.db.JobEntity
import org.cassini.data.db.offMain
import org.cassini.data.notes.NotesHarness
import org.cassini.data.notes.SealedAdopter
import org.cassini.data.notes.documentBytes
import org.cassini.data.notes.transcript
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
import java.io.File

/** Every row of the architecture 5.3 table, over real notes, checkpoints and a real adopt. */
@RunWith(RobolectricTestRunner::class)
class ReconcileOnStartTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var h: NotesHarness
    private lateinit var checkpoints: CheckpointRepository
    private lateinit var jobs: JobRepository

    private val sealed = noteId(1)
    private val badSeal = noteId(2)
    private val runningBare = noteId(3)
    private val runningSaved = noteId(4)
    private val runningThird = noteId(5)
    private val runningBareThird = noteId(6)
    private val pausedBare = noteId(7)
    private val pausedSaved = noteId(8)
    private val runningMismatch = noteId(9)
    private val pausedMismatch = noteId(10)
    private val orphanWork = noteId(11)
    private val active = noteId(12)
    private val queued = noteId(13)
    private val failed = noteId(14)

    @Before fun setUp() {
        h = NotesHarness(folder)
        checkpoints = CheckpointRepository(h.db, h.paths, h.dispatchers, h.locks)
        jobs = JobRepository(
            h.db,
            h.paths,
            h.dispatchers,
            h.locks,
            checkpoints,
            SealedAdopter { h.documents.adoptSealed(it) },
        ) { h.now }
    }

    @After fun tearDown() = h.close()

    private var seq = 0L

    private suspend fun row(
        id: String,
        state: JobState?,
        attempts: Int = 0,
        reason: PauseReason? = null,
        doneMs: Long = 0,
    ) {
        h.plainNote(id)
        if (state == null) return
        h.db.jobs().insert(
            JobEntity(
                noteId = id,
                seq = ++seq,
                kind = JobKind.TRANSCRIBE,
                state = state,
                pauseReason = reason,
                doneMs = doneMs,
                words = if (doneMs > 0) 50 else 0,
                attempts = attempts,
                enqueuedAt = 1,
                updatedAt = 1,
            ),
        )
    }

    private fun save(id: String, model: String? = null) = offMain {
        checkpoints.saveBlocking(id, if (model == null) h.checkpoint(id) else h.checkpoint(id, model = model))
    }

    private fun seal(id: String, bytes: ByteArray) {
        val work = h.paths.work(id).apply { mkdirs() }
        File(work, "sealed.opus").writeBytes(bytes)
        File(work, "sealed").createNewFile()
    }

    private suspend fun job(id: String): JobEntity = requireNotNull(jobs.job(id)) { "no job $id" }

    private suspend fun writeTree() {
        row(sealed, JobState.RUNNING)
        save(sealed)
        seal(sealed, documentBytes(transcript("la seduta è aperta"), "Verbale"))

        row(badSeal, JobState.RUNNING)
        save(badSeal)
        seal(badSeal, "not a document".toByteArray())

        row(runningBare, JobState.RUNNING)
        row(runningSaved, JobState.RUNNING, attempts = 1)
        save(runningSaved)
        row(runningThird, JobState.RUNNING, attempts = 2)
        save(runningThird)
        row(runningBareThird, JobState.RUNNING, attempts = 2)

        row(pausedBare, JobState.PAUSED, reason = PauseReason.USER, doneMs = 40_000)
        row(pausedSaved, JobState.PAUSED, reason = PauseReason.USER, doneMs = 28_000)
        save(pausedSaved)

        row(runningMismatch, JobState.RUNNING)
        save(runningMismatch, model = "an older parakeet revision")
        row(pausedMismatch, JobState.PAUSED, reason = PauseReason.MEMORY, doneMs = 28_000)
        save(pausedMismatch)
        File(h.dir(pausedMismatch), "capture.opus").setLastModified(1_000)

        row(orphanWork, null)
        save(orphanWork)
        checkpoints.clips(orphanWork).writeBytes(ByteArray(64))

        row(active, JobState.RUNNING)
        row(queued, JobState.QUEUED)
        save(queued, model = "stale")
        row(failed, JobState.FAILED)
        save(failed, model = "stale")
    }

    @Test fun everyRowOfTheTable() = runTest {
        writeTree()

        val report = jobs.reconcileOnStart(activeNoteId = active)

        // A marker with a sealed output that reads OK: adopt finishes and the job goes.
        assertNull(jobs.job(sealed))
        assertEquals(AudioKind.DOCUMENT, h.row(sealed).audioKind)
        assertFalse(h.paths.work(sealed).exists())
        assertTrue(h.isOk(sealed))
        // A marker with an unreadable output: both go, then the RUNNING rule applies.
        assertFalse(File(h.paths.work(badSeal), "sealed").exists())
        assertEquals(JobState.QUEUED to 1, job(badSeal).state to job(badSeal).attempts)
        assertEquals(AudioKind.PLAIN, h.row(badSeal).audioKind)

        // RUNNING without a checkpoint: QUEUED, the same seq, attempts + 1.
        job(runningBare).let {
            assertEquals(JobState.QUEUED, it.state)
            assertEquals(3L, it.seq)
            assertEquals(1, it.attempts)
        }
        // RUNNING with a valid checkpoint: QUEUED at its seq, attempts + 1, the checkpoint's numbers.
        job(runningSaved).let {
            assertEquals(JobState.QUEUED, it.state)
            assertEquals(4L, it.seq)
            assertEquals(2, it.attempts)
            assertEquals(28_000L, it.doneMs)
            assertEquals(3, it.words)
        }
        // Above two attempts: PAUSED(INTERRUPTED), the checkpoint kept.
        job(runningThird).let {
            assertEquals(JobState.PAUSED to PauseReason.INTERRUPTED, it.state to it.pauseReason)
            assertEquals(3, it.attempts)
        }
        assertTrue(checkpoints.load(runningThird) is CheckpointLoad.Valid)
        // The same limit without a checkpoint (a crash during model load would otherwise loop).
        job(runningBareThird).let {
            assertEquals(JobState.PAUSED to PauseReason.INTERRUPTED, it.state to it.pauseReason)
            assertEquals(3, it.attempts)
        }

        // PAUSED without a checkpoint: stays PAUSED, done 0.
        job(pausedBare).let {
            assertEquals(JobState.PAUSED to PauseReason.USER, it.state to it.pauseReason)
            assertEquals(0L to 0, it.doneMs to it.words)
        }
        // PAUSED with a valid checkpoint: unchanged.
        assertEquals(JobState.PAUSED to 28_000L, job(pausedSaved).state to job(pausedSaved).doneMs)
        assertEquals(0, job(pausedSaved).attempts)

        // A checkpoint that fails validation: FAILED(CHECKPOINT_MISMATCH); the files stay for Start over.
        listOf(runningMismatch, pausedMismatch).forEach { id ->
            assertEquals(JobState.FAILED, job(id).state)
            assertEquals(JobRepository.CHECKPOINT_MISMATCH, job(id).failure)
            assertNull(job(id).pauseReason)
            assertTrue(File(h.paths.work(id), "checkpoint.json").isFile)
        }

        // work/ without a row and without a marker: deleted, the audio kept.
        assertFalse(h.paths.work(orphanWork).exists())
        assertTrue(File(h.dir(orphanWork), "capture.opus").isFile)

        // The active capture, QUEUED and FAILED rows are not touched.
        assertEquals(JobState.RUNNING to 0, job(active).state to job(active).attempts)
        assertEquals(JobState.QUEUED to 0, job(queued).state to job(queued).attempts)
        assertEquals(JobState.FAILED, job(failed).state)
        assertNull(job(failed).failure)

        assertEquals(listOf(sealed), report.adopted)
        assertEquals(listOf(badSeal, runningBare, runningSaved).sorted(), report.requeued.sorted())
        assertEquals(listOf(runningThird, runningBareThird).sorted(), report.interrupted.sorted())
        assertEquals(listOf(pausedBare), report.restarted)
        assertEquals(listOf(runningMismatch, pausedMismatch).sorted(), report.mismatched.sorted())
        assertEquals(listOf(orphanWork), report.deletedWork)
    }

    @Test fun aSecondRunChangesNothing() = runTest {
        writeTree()
        jobs.reconcileOnStart(activeNoteId = active)
        val before = h.db.jobs().all()

        val report = jobs.reconcileOnStart(activeNoteId = active)

        assertEquals(before, h.db.jobs().all())
        assertEquals(ReconcileReport(), report)
    }

    @Test fun aMarkerWithoutARowIsAdopted() = runTest {
        row(sealed, null)
        seal(sealed, documentBytes(transcript("la seduta è aperta"), "Verbale"))

        assertEquals(listOf(sealed), jobs.reconcileOnStart(null).adopted)
        assertEquals(AudioKind.DOCUMENT, h.row(sealed).audioKind)
        assertFalse(h.paths.work(sealed).exists())
    }

    @Test fun anAdoptedNoteForgetsItsDraftNames() = runTest {
        row(sealed, JobState.RUNNING)
        save(sealed)
        checkpoints.draftNames(sealed).mutate { it.rename("S1", "Anna") }
        seal(sealed, documentBytes(transcript("la seduta è aperta"), "Verbale"))

        jobs.reconcileOnStart(null)

        assertTrue(checkpoints.draftNames(sealed).current().labels.isEmpty())
    }
}
