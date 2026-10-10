package org.cassini.data.checkpoint

import android.util.AtomicFile
import kotlinx.coroutines.test.runTest
import org.cassini.core.checkpoint.CheckpointState
import org.cassini.core.speakers.Match
import org.cassini.data.db.offMain
import org.cassini.data.notes.NotesHarness
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.RandomAccessFile

/** CheckpointRepository (architecture 3.5 and 4.4) over a scratch root. */
@RunWith(RobolectricTestRunner::class)
class CheckpointRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var h: NotesHarness
    private lateinit var repo: CheckpointRepository
    private val id = noteId(1)

    @Before fun setUp() = runTest {
        h = NotesHarness(folder)
        repo = CheckpointRepository(h.db, h.paths, h.dispatchers, h.locks)
        h.plainNote(id)
    }

    @After fun tearDown() = h.close()

    private val identity get() = CheckpointIdentity.of(h.dir(id), "capture.opus")
    private val checkpointFile get() = File(h.paths.work(id), "checkpoint.json")

    private fun save(state: CheckpointState) = offMain { repo.saveBlocking(id, state) }

    private fun load(expected: CheckpointIdentity = identity) = offMain { repo.loadBlocking(id, expected) }

    private fun reason(load: CheckpointLoad): MismatchReason? = (load as? CheckpointLoad.Mismatch)?.reason

    @Test fun savedCheckpointLoadsValid() {
        assertEquals(CheckpointLoad.Missing, load())
        val state = h.checkpoint(id)
        save(state)

        val loaded = load() as CheckpointLoad.Valid
        assertEquals(state.doneMs, loaded.state.doneMs)
        assertEquals(state.recognition.chunks.words, loaded.state.recognition.chunks.words)
        assertEquals(CheckpointState.encode(state).toString(), CheckpointState.encode(loaded.state).toString())
    }

    @Test fun blockingCallsRefuseTheMainThread() {
        assertThrows(IllegalStateException::class.java) { repo.saveBlocking(id, h.checkpoint(id)) }
        assertThrows(IllegalStateException::class.java) { repo.loadBlocking(id, identity) }
    }

    @Test fun anInterruptedWriteKeepsTheLastCheckpoint() {
        val first = h.checkpoint(id, doneMs = 28_000)
        save(first)

        // A crash in the middle of the next write: AtomicFile's new file is half written and never committed.
        val atomic = AtomicFile(checkpointFile)
        val output = atomic.startWrite()
        output.write(CheckpointState.encode(h.checkpoint(id, doneMs = 54_000)).toString().take(100).toByteArray())
        output.fd.sync()
        output.close()

        assertEquals(28_000L, (load() as CheckpointLoad.Valid).state.doneMs)
        // The next save commits normally over the leftover.
        save(h.checkpoint(id, doneMs = 80_000))
        assertEquals(80_000L, (load() as CheckpointLoad.Valid).state.doneMs)
    }

    @Test fun aCrashOfTheLegacyBackupWriteIsRecovered() {
        save(h.checkpoint(id, doneMs = 28_000))
        // The legacy AtomicFile path renames the file to .bak, then writes a new one; it crashed half way.
        val backup = File(checkpointFile.path + ".bak")
        assertTrue(checkpointFile.renameTo(backup))
        checkpointFile.writeText("{\"recognition\": {\"inferen")

        assertEquals(28_000L, (load() as CheckpointLoad.Valid).state.doneMs)

        // The crash came before the new file was created: only the backup is left, and it is still the checkpoint.
        assertTrue(checkpointFile.renameTo(backup))
        assertFalse(checkpointFile.exists())
        assertEquals(28_000L, (load() as CheckpointLoad.Valid).state.doneMs)
    }

    @Test fun saveSyncsTheAppendOnlyFilesFirst() {
        val synced = mutableListOf<String>()
        repo = CheckpointRepository(h.db, h.paths, h.dispatchers, h.locks) { file ->
            assertFalse("synced before checkpoint.json is written", checkpointFile.exists())
            synced += file.name
        }
        val clips = repo.clips(id).apply { parentFile!!.mkdirs() }
        clips.writeBytes(ByteArray(4 * 16_000))
        repo.audioPart(id).writeBytes(ByteArray(500))
        save(h.checkpoint(id, clipSamples = 16_000, encoderLength = 500))

        assertEquals(listOf("audio.opus.part", "clips.pcm"), synced)
        assertTrue(load() is CheckpointLoad.Valid)
    }

    @Test fun mismatchReasons() {
        repo.clips(id).apply { parentFile!!.mkdirs() }.writeBytes(ByteArray(4 * 1_000))
        repo.audioPart(id).writeBytes(ByteArray(500))
        save(h.checkpoint(id, clipSamples = 1_000, encoderLength = 500))
        assertTrue(load() is CheckpointLoad.Valid)

        assertEquals(MismatchReason.SOURCE, reason(load(identity.copy(source = "source.mp3"))))
        assertEquals(MismatchReason.MODEL, reason(load(identity.copy(modelRevision = "another-revision"))))
        assertEquals(MismatchReason.SPEAKERS, reason(load(identity.copy(speakersSha256 = "another-sha"))))

        // The audio file changed after the checkpoint.
        File(h.dir(id), "capture.opus").setLastModified(1_000)
        assertEquals(MismatchReason.SIGNATURE, reason(load()))
        save(h.checkpoint(id, clipSamples = 1_000, encoderLength = 500))

        // clips.pcm lost its tail: fewer bytes than the committed clip samples.
        RandomAccessFile(repo.clips(id), "rw").use { it.setLength(3_999) }
        assertEquals(MismatchReason.CLIPS, reason(load()))
        repo.clips(id).writeBytes(ByteArray(4 * 1_000))

        // audio.opus.part is shorter than the encoder's committed length.
        RandomAccessFile(repo.audioPart(id), "rw").use { it.setLength(499) }
        assertEquals(MismatchReason.AUDIO, reason(load()))
        repo.audioPart(id).writeBytes(ByteArray(500))
        assertTrue(load() is CheckpointLoad.Valid)

        edit { it.put("version", 2) }
        assertEquals(MismatchReason.VERSION, reason(load()))
        save(h.checkpoint(id, clipSamples = 1_000, encoderLength = 500))

        edit { it.put("doneMs", -5) }
        assertEquals(MismatchReason.RANGE, reason(load()))

        edit { it.remove("model") }
        assertEquals(MismatchReason.UNREADABLE, reason(load()))

        checkpointFile.writeText("not json")
        assertEquals(MismatchReason.UNREADABLE, reason(load()))
    }

    private fun edit(change: (JSONObject) -> Unit) {
        val json = JSONObject(checkpointFile.readText())
        change(json)
        checkpointFile.writeText(json.toString())
    }

    @Test fun statusShowsEachState() = runTest {
        assertEquals(CheckpointStatus.None, repo.status(id))
        assertEquals(CheckpointStatus.NoNote, repo.status(noteId(99)))

        save(h.checkpoint(id, words = draftWords("S1", "S2"), doneMs = 30_000))
        assertEquals(CheckpointStatus.Saved(30_000, 60_000, 2), repo.status(id))

        File(h.dir(id), "capture.opus").setLastModified(1_000)
        assertEquals(CheckpointStatus.Mismatch(MismatchReason.SIGNATURE), repo.status(id))

        File(h.paths.work(id), "sealed").createNewFile()
        assertTrue(repo.sealedMarker(id))
        assertEquals(CheckpointStatus.SealedAwaitingAdopt, repo.status(id))
    }

    @Test fun draftAppliesMergesAndLabels() = runTest {
        save(h.checkpoint(id, words = draftWords("S1", "S2", "S3", "S2")))
        repo.draftNames(id).mutate { names ->
            names.rename("S1", "Anna")
                .merge("S3", "S2")
                .applyLive(mapOf("S2" to Match("voice-luca", 0.9f, Match.State.AUTO)), mapOf("voice-luca" to "Luca"))
        }

        val draft = requireNotNull(repo.loadDraft(id))
        assertEquals(listOf("S1", "S2", "S2", "S2"), draft.words.map { it.speaker })
        assertEquals(mapOf("S1" to "Anna", "S2" to "Luca"), draft.labels)
        assertEquals(28_000L, draft.doneMs)
        assertNull(repo.loadDraft(noteId(2)))
    }

    @Test fun draftTreatsAStoredPlaceholderAsNoName() = runTest {
        save(h.checkpoint(id, words = draftWords("S1", "S2", "S3")))
        // As a names.json written elsewhere could hold them: placeholders are not names.
        repo.draftNames(id).mutate { names ->
            names.copy(
                labels = mapOf("S1" to "Speaker 1", "S2" to "Voce 2", "S3" to "Anna"),
                automatic = mapOf("S1" to "Luca"),
            )
        }

        assertEquals(mapOf("S1" to "Luca", "S3" to "Anna"), requireNotNull(repo.loadDraft(id)).labels)
    }

    @Test fun discardDeletesASealedOutputAndItsMarker() = runTest {
        save(h.checkpoint(id))
        val work = h.paths.work(id)
        File(work, "sealed.opus").writeBytes(ByteArray(8))
        File(work, "sealed").createNewFile()

        repo.discard(id)

        assertFalse(work.exists())
        assertFalse(repo.sealedMarker(id))
    }

    @Test fun discardDeletesTheWorkDirectoryAndTheNamesInMemory() = runTest {
        save(h.checkpoint(id))
        repo.draftNames(id).mutate { it.rename("S1", "Anna") }
        repo.clips(id).writeBytes(ByteArray(8))

        repo.discard(id)

        assertFalse(h.paths.work(id).exists())
        assertEquals(CheckpointLoad.Missing, load())
        assertEquals(NamesStore.EMPTY, repo.draftNames(id).current())
        assertEquals(CheckpointStatus.None, repo.status(id))
    }
}
