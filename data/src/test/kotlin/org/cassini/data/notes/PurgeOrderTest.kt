package org.cassini.data.notes

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.cassini.core.library.JobKind
import org.cassini.core.speakers.Match
import org.cassini.core.speakers.NoteVoiceCodec
import org.cassini.core.speakers.SpeakerPrint
import org.cassini.data.checkpoint.CheckpointRepository
import org.cassini.data.checkpoint.noteId
import org.cassini.data.db.offMain
import org.cassini.data.jobs.JobRepository
import org.cassini.data.jobs.RunningJobCanceller
import org.cassini.data.voices.VoiceRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.util.Collections

/**
 * The trash purge (architecture 4.10, features.md section 8) with the real job and voice repositories: the job, the
 * voice files, the note directory, then the row. A crash midway leaves a trashed row that the next run finishes, and
 * a catalogue rebuild never brings a purged note back.
 */
@RunWith(RobolectricTestRunner::class)
class PurgeOrderTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var h: NotesHarness
    private lateinit var checkpoints: CheckpointRepository
    private lateinit var voices: VoiceRepository
    private lateinit var jobs: JobRepository
    private val events: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Set to make the cleaner fail after it deleted the voice files, as a process death there would. */
    private var crashAfterVoices = false

    /** Runs at the start of each purge step 1, before the job goes: a point where another caller can interleave. */
    private var onCancel: suspend (String) -> Unit = {}

    private val purged = noteId(1)
    private val live = noteId(2)
    private val day = 24 * 60 * 60 * 1000L

    @Before fun setUp() = runTest {
        h = NotesHarness(folder)
        checkpoints = CheckpointRepository(h.db, h.paths, h.dispatchers, h.locks)
        voices = VoiceRepository(h.db, h.paths, h.dispatchers, checkpoints.names) { h.now }
        val adopter = SealedAdopter.of(h.documents, voices)
        val canceller = RunningJobCanceller {}
        jobs = JobRepository(h.db, h.paths, h.dispatchers, h.locks, checkpoints, adopter, canceller) { h.now }
        h.plainNote(purged)
        h.plainNote(live)
        jobs.enqueue(purged, JobKind.TRANSCRIBE)
        writePrints(purged)
        writePrints(live)
        File(File(h.paths.voices, "pending"), "$purged.json").apply { parentFile!!.mkdirs() }.writeText("{}")
        voices.syncLinks()
    }

    @After fun tearDown() = h.close()

    private fun writePrints(id: String) {
        val print = SpeakerPrint(FloatArray(4) { 0.5f }, 10.0, Match("voice_luca", 0.8f, Match.State.AUTO))
        File(File(h.paths.voices, "notes"), "$id.json").apply { parentFile!!.mkdirs() }
            .writeText(NoteVoiceCodec.encode("model", mapOf("v1/S1" to print)))
    }

    /** The note repository with the real queue and voice store, recording what each purge step finds. */
    private fun notes(): NoteRepository {
        val queue = object : JobQueueWriter {
            override suspend fun requeueAtBack(noteId: String, kind: JobKind) = jobs.requeueAtBack(noteId, kind)

            override suspend fun cancelAndRemove(noteId: String) {
                events += "job voices=${voiceFile(noteId).exists()}"
                onCancel(noteId)
                jobs.cancelAndRemove(noteId)
            }
        }
        val cleaner = NoteFilesCleaner { noteId ->
            events += "voices job=${h.db.jobs().job(noteId) != null} dir=${h.dir(noteId).exists()}"
            voices.deleteForNote(noteId).also { if (crashAfterVoices) throw IOException("process died") }
        }
        return NoteRepository(
            h.db,
            h.paths,
            h.dispatchers,
            h.locks,
            h.documents,
            h.background,
            queue,
            cleaner,
        ) { h.now }
    }

    private fun voiceFile(id: String) = File(File(h.paths.voices, "notes"), "$id.json")

    private fun pendingFile(id: String) = File(File(h.paths.voices, "pending"), "$id.json")

    private fun catalogue() =
        CatalogueRepository(h.db, h.paths, h.dispatchers, h.locks, h.documents, voices, FakeProbe()) { h.now }

    private suspend fun trashLongAgo(repo: NoteRepository) {
        repo.moveToTrash(purged)
        h.now += 31 * day
    }

    private suspend fun purgeNow(repo: NoteRepository) =
        repo.purgeExpired(Instant.ofEpochMilli(h.now), ZoneOffset.UTC)

    @Test fun eachStepRunsBeforeTheNext() = runTest {
        val repo = notes()
        repo.moveToTrash(purged)

        assertTrue(repo.deleteNow(purged))

        assertEquals(listOf("job voices=true", "voices job=false dir=true"), events)
        assertNull(h.db.jobs().job(purged))
        assertFalse(voiceFile(purged).exists())
        assertFalse(pendingFile(purged).exists())
        assertFalse(h.dir(purged).exists())
        assertNull(h.db.notes().note(purged))
        assertTrue(h.db.voiceLinks().links(purged).isEmpty())
        // The other note keeps everything.
        assertTrue(voiceFile(live).exists() && h.dir(live).exists())
        assertEquals(1, h.db.voiceLinks().links(live).size)
        assertFalse("a live note is not deleted now", repo.deleteNow(live))
    }

    @Test fun aCrashAfterTheVoiceFilesIsFinishedByTheNextRun() = runTest {
        crashAfterVoices = true
        trashLongAgo(notes())

        assertTrue(runCatching { purgeNow(notes()) }.exceptionOrNull() is IOException)
        assertFalse(voiceFile(purged).exists())
        assertTrue("the audio is still there", h.dir(purged).exists())
        assertNotNull(h.db.notes().note(purged)?.trashedAt)

        crashAfterVoices = false
        h.restart()
        assertEquals(1, purgeNow(notes()))
        assertFalse(h.dir(purged).exists())
        assertNull(h.db.notes().note(purged))
    }

    @Test fun aCrashAfterTheFileDeleteIsFinishedByTheNextRun() = runTest {
        trashLongAgo(notes())
        // The steps a purge took before the process died: job, voice files, note directory. The row is still there.
        jobs.cancelAndRemove(purged)
        assertTrue(voices.deleteForNote(purged))
        assertTrue(h.documents.deleteFiles(purged))

        h.restart()
        assertEquals("the catalogue has rows, so nothing is rebuilt", null, catalogue().rebuildIfNeeded(null))
        assertTrue(catalogue().rebuild().created.isEmpty())
        assertEquals(1, purgeNow(notes()))

        assertNull(h.db.notes().note(purged))
        assertNotNull(h.db.notes().note(live))
        assertEquals(0, purgeNow(notes()))
    }

    @Test fun aRebuildAfterTheFileDeleteDoesNotBringTheNoteBack() = runTest {
        trashLongAgo(notes())
        jobs.cancelAndRemove(purged)
        voices.deleteForNote(purged)
        h.documents.deleteFiles(purged)
        // The catalogue is lost as well: the next start rebuilds it from the note directories.
        offMain { h.db.clearAllTables() }

        val report = catalogue().rebuildIfNeeded(null)

        assertEquals(listOf(live), report?.created)
        assertNull(h.db.notes().note(purged))
        assertFalse(h.dir(purged).exists())
        assertTrue(voices.syncLinks().deleted.isEmpty())
        assertEquals(1, h.db.voiceLinks().all().size)
    }

    /** The state of a note that a restore brought back: its row is live and it keeps its files and links. */
    private suspend fun assertKept(id: String) {
        val row = h.db.notes().note(id)
        assertNotNull(row)
        assertNull(row!!.trashedAt)
        assertTrue(h.dir(id).exists() && voiceFile(id).exists())
        assertEquals(1, h.db.voiceLinks().links(id).size)
    }

    @Test fun aRestoreAfterTheTrashSnapshotKeepsTheNote() = runTest {
        val repo = notes()
        repo.moveToTrash(purged)
        h.now += 1
        repo.moveToTrash(live)
        // Empty took its list of trashed notes; the user restores the second note while the first one goes.
        var restored = false
        onCancel = { id -> if (id == purged) restored = repo.restore(live) }

        assertEquals(1, repo.emptyTrash())

        assertTrue(restored)
        assertKept(live)
        assertNull(h.db.notes().note(purged))
    }

    @Test fun anExpiredNoteRestoredAndTrashedAgainIsNotPurged() = runTest {
        val repo = notes()
        repo.moveToTrash(purged)
        repo.moveToTrash(live)
        h.now += 31 * day
        // After the expiry query, the second note goes back to the Library and then to Trash again: its 30 days start
        // again.
        onCancel = { id ->
            if (id == purged) {
                check(repo.restore(live))
                check(repo.moveToTrash(live))
            }
        }

        assertEquals(1, purgeNow(repo))

        assertNotNull(h.db.notes().note(live)?.trashedAt)
        assertTrue(h.dir(live).exists() && voiceFile(live).exists())
    }

    @Test fun aRestoreDuringThePurgeOfTheSameNoteIsRefused() = runTest {
        val repo = notes()
        repo.moveToTrash(purged)
        var restored: Boolean? = null
        onCancel = { id -> if (id == purged) restored = repo.restore(purged) }

        assertTrue(repo.deleteNow(purged))

        assertEquals("a note that the purge took cannot come back", false, restored)
        assertNull(h.db.notes().note(purged))
        assertFalse(h.dir(purged).exists())
    }

    @Test fun aRestoreRacingThePurgeNeverLosesARestoredNote() = runTest {
        val repo = notes()
        repo.moveToTrash(purged)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        onCancel = {
            started.complete(Unit)
            release.await()
        }
        val purge = async(Dispatchers.IO) { repo.deleteNow(purged) }
        started.await()
        // The restore runs on another thread while the purge waits in its first step.
        val restored = withContext(Dispatchers.IO) { repo.restore(purged) }
        release.complete(Unit)
        val deleted = purge.await()

        assertTrue("exactly one of them wins", restored != deleted)
        if (restored) assertKept(purged) else assertNull(h.db.notes().note(purged))
    }

    @Test fun aRestoreAfterAFailedPurgeBringsTheNoteBack() = runTest {
        crashAfterVoices = true
        val repo = notes()
        repo.moveToTrash(purged)
        assertTrue(runCatching { repo.deleteNow(purged) }.exceptionOrNull() is IOException)

        assertTrue("the purge gave the note back", repo.restore(purged))
        assertNull(h.db.notes().note(purged)?.trashedAt)
        assertTrue(h.dir(purged).exists())
    }
}
