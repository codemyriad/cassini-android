package org.cassini.data.jobs

import kotlinx.coroutines.test.runTest
import org.cassini.core.library.AudioKind
import org.cassini.core.library.JobKind
import org.cassini.core.library.JobState
import org.cassini.core.library.PauseReason
import org.cassini.core.models.ModelCatalog
import org.cassini.core.speakers.NoteVoiceCodec
import org.cassini.core.speakers.SpeakerPrint
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
import org.cassini.data.voices.VoiceRepository
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
    private lateinit var voices: VoiceRepository
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
        voices = VoiceRepository(h.db, h.paths, h.dispatchers, checkpoints.names) { h.now }
        jobs = JobRepository(
            h.db,
            h.paths,
            h.dispatchers,
            h.locks,
            checkpoints,
            SealedAdopter.of(h.documents, voices),
            RunningJobCanceller {},
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

    @Test fun aCrashAfterTheRowUpdateOfAnAdoptIsFinishedWithoutAnotherRun() = runTest {
        row(sealed, JobState.RUNNING)
        save(sealed)
        seal(sealed, documentBytes(transcript("la seduta è aperta"), "Verbale"))
        val print = SpeakerPrint(FloatArray(ModelCatalog.VOICEPRINT_DIM) { 0.5f }, 10.0, null)
        assertTrue(offMain { voices.savePendingBlocking(sealed, mapOf("words/S1" to print), 0) })
        // The process died after adoptSealed: the document and the rows are in place, the job row is gone,
        // and work/ with its marker and the pending prints are still there.
        h.documents.adoptSealed(sealed)
        assertNull(jobs.job(sealed))

        val report = jobs.reconcileOnStart(null)

        assertEquals(listOf(sealed), report.adopted)
        assertEquals(emptyList<String>(), report.requeued)
        assertNull(jobs.job(sealed))
        assertFalse(h.paths.work(sealed).exists())
        assertFalse(File(h.paths.voices, "pending/$sealed.json").exists())
        assertTrue(File(h.paths.voices, "notes/$sealed.json").isFile)
    }

    @Test fun anAdoptedNoteForgetsItsDraftNames() = runTest {
        row(sealed, JobState.RUNNING)
        save(sealed)
        checkpoints.draftNames(sealed).mutate { it.rename("S1", "Anna") }
        seal(sealed, documentBytes(transcript("la seduta è aperta"), "Verbale"))

        jobs.reconcileOnStart(null)

        assertTrue(checkpoints.draftNames(sealed).current().labels.isEmpty())
    }

    @Test fun theAdopterAloneForgetsTheDraftNames() = runTest {
        row(sealed, JobState.RUNNING)
        save(sealed)
        checkpoints.draftNames(sealed).mutate { it.rename("S1", "Anna") }
        seal(sealed, documentBytes(transcript("la seduta è aperta"), "Verbale"))

        SealedAdopter.of(h.documents, voices).adopt(sealed)

        assertTrue(checkpoints.draftNames(sealed).current().labels.isEmpty())
    }

    @Test fun aTrashedQueuedNoteKeepsItsCheckpointForRestore() = runTest {
        row(queued, JobState.QUEUED, doneMs = 28_000)
        save(queued)
        assertTrue(h.db.notes().trash(queued, h.now))
        assertNull("Trash deletes a QUEUED row", jobs.job(queued))

        val report = jobs.reconcileOnStart(null)

        assertTrue(report.deletedWork.isEmpty())
        assertTrue(checkpoints.load(queued) is CheckpointLoad.Valid)
        // Restore puts the job back with the numbers of the checkpoint.
        assertTrue(h.db.notes().untrash(queued, h.now) != null)
        jobs.requeueAtBack(queued, JobKind.TRANSCRIBE)
        job(queued).let {
            assertEquals(JobState.QUEUED, it.state)
            assertEquals(28_000L to 3, it.doneMs to it.words)
        }
        assertTrue(checkpoints.load(queued) is CheckpointLoad.Valid)
    }

    @Test fun aVoiceFileThatCannotBeReadKeepsTheMarkerForTheNextStart() = runTest {
        row(sealed, JobState.RUNNING)
        save(sealed)
        seal(sealed, documentBytes(transcript("la seduta è aperta"), "Verbale"))
        val print = SpeakerPrint(FloatArray(ModelCatalog.VOICEPRINT_DIM) { 0.5f }, 10.0, null)
        assertTrue(offMain { voices.savePendingBlocking(sealed, mapOf("words/S1" to print), 0) })
        val model = ModelCatalog.voiceprintModel.sha256
        val notePrints = File(h.paths.voices, "notes/$sealed.json").apply { parentFile!!.mkdirs() }
        notePrints.writeText(NoteVoiceCodec.encode(model, mapOf("old/S1" to print)))
        val pending = File(h.paths.voices, "pending/$sealed.json")

        assertTrue(notePrints.setReadable(false))
        try {
            assertEquals(listOf(sealed), jobs.reconcileOnStart(null).adopted)
            assertEquals(AudioKind.DOCUMENT, h.row(sealed).audioKind)
            assertTrue("the marker stays for a retry", File(h.paths.work(sealed), "sealed").isFile)
            assertTrue(pending.isFile)
        } finally {
            notePrints.setReadable(true)
        }

        assertEquals(listOf(sealed), jobs.reconcileOnStart(null).adopted)
        assertFalse(h.paths.work(sealed).exists())
        assertFalse(pending.exists())
        assertEquals(setOf("old/S1", "words/S1"), NoteVoiceCodec.decode(notePrints.readText()).second.keys)
    }

    @Test fun dropSealedKeepsTheProgressAtEveryStep() = runTest {
        row(pausedSaved, JobState.PAUSED, reason = PauseReason.USER, doneMs = 28_000)
        save(pausedSaved)
        checkpoints.clips(pausedSaved).writeBytes(ByteArray(64))
        seal(pausedSaved, documentBytes(transcript("la seduta è aperta"), "Verbale"))
        val work = h.paths.work(pausedSaved)
        // The process died after the first step of dropSealed: the marker is gone, sealed.opus is not.
        assertTrue(File(work, "sealed").delete())

        val report = jobs.reconcileOnStart(null)

        assertTrue(report.adopted.isEmpty())
        assertTrue(report.deletedWork.isEmpty())
        assertEquals(JobState.PAUSED to PauseReason.USER, job(pausedSaved).state to job(pausedSaved).pauseReason)
        assertEquals(AudioKind.PLAIN, h.row(pausedSaved).audioKind)
        assertTrue(checkpoints.load(pausedSaved) is CheckpointLoad.Valid)

        assertTrue(checkpoints.dropSealed(pausedSaved))
        assertFalse(File(work, "sealed.opus").exists())
        assertEquals(64L, checkpoints.clips(pausedSaved).length())
        assertTrue(checkpoints.load(pausedSaved) is CheckpointLoad.Valid)
        assertTrue(checkpoints.dropSealed(pausedSaved))
    }

    @Test fun aNewJobNeverStartsOverAnAdoptThatDidNotFinish() = runTest {
        row(sealed, JobState.RUNNING)
        save(sealed)
        seal(sealed, documentBytes(transcript("la seduta è aperta"), "Verbale"))
        val print = SpeakerPrint(FloatArray(ModelCatalog.VOICEPRINT_DIM) { 0.5f }, 10.0, null)
        assertTrue(offMain { voices.savePendingBlocking(sealed, mapOf("words/S1" to print), 0) })
        // The voice step of the adopt fails: the document is in place, the job row is gone, the marker stays.
        val notes = File(h.paths.voices, "notes").apply { mkdirs() }
        assertTrue(notes.setWritable(false))
        try {
            SealedAdopter.of(h.documents, voices).adopt(sealed)
            assertNull(jobs.job(sealed))
            assertTrue(File(h.paths.work(sealed), "sealed").isFile)

            assertEquals(
                EnqueueOutcome.Refused(EnqueueRefusal.ADOPT_PENDING),
                jobs.enqueue(sealed, JobKind.TRANSCRIBE_AGAIN),
            )
        } finally {
            notes.setWritable(true)
        }

        // The enqueue finishes the adopt first, so the new job starts with an empty work directory.
        assertTrue(jobs.enqueue(sealed, JobKind.TRANSCRIBE_AGAIN) is EnqueueOutcome.Queued)
        assertFalse(h.paths.work(sealed).exists())
        assertTrue(File(h.paths.voices, "notes/$sealed.json").isFile)
        // The new job reads the adopted document.
        offMain { checkpoints.saveBlocking(sealed, h.checkpoint(sealed, source = "document.opus")) }

        val report = jobs.reconcileOnStart(null)

        assertTrue(report.adopted.isEmpty())
        assertEquals(JobState.QUEUED, job(sealed).state)
        assertTrue(checkpoints.load(sealed) is CheckpointLoad.Valid)
    }
}
