package org.cassini.data.notes

import kotlinx.coroutines.test.runTest
import org.cassini.core.format.CassiniDocument
import org.cassini.core.format.DocumentState
import org.cassini.core.models.ModelCatalog
import org.cassini.core.speakers.SpeakerPrint
import org.cassini.core.speakers.Voiceprints
import org.cassini.data.checkpoint.CheckpointRepository
import org.cassini.data.checkpoint.noteId
import org.cassini.data.db.offMain
import org.cassini.data.voices.VoiceRepository
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
import kotlin.random.Random

/**
 * The blocking entry points the job's storage ports use (the :app adapters of architecture 5.4): the marker-only
 * seal step that remembers the verified document, the blocking document read, and the blocking pending and print
 * reads of the voice files.
 */
@RunWith(RobolectricTestRunner::class)
class JobEntryPointsTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var h: NotesHarness
    private lateinit var voices: VoiceRepository
    private val id = noteId(7)

    @Before fun setUp() {
        h = NotesHarness(folder)
        val names = CheckpointRepository(h.db, h.paths, h.dispatchers, h.locks).names
        voices = VoiceRepository(h.db, h.paths, h.dispatchers, names)
    }

    @After fun tearDown() = h.close()

    /** Writes `work/sealed.opus` over the note's capture as the job does, and returns the verified read. */
    private fun sealAsTheJobDoes(): CassiniDocument {
        val work = h.paths.work(id).apply { mkdirs() }
        return CassiniDocument.create(
            File(h.dir(id), "capture.opus"),
            transcript("uno due tre quattro cinque sei"),
            "Riunione",
            JSONObject().put("engine", "test"),
            null,
            emptyMap(),
            File(work, NoteFiles.SEALED),
        )
    }

    @Test fun markSealedWritesTheMarkerAndAdoptDoesNotReadTheFileAgain() = runTest {
        h.plainNote(id)
        val document = offMain { sealAsTheJobDoes() }
        offMain { h.documents.markSealedBlocking(id, document) }
        assertTrue(File(h.paths.work(id), NoteFiles.SEALED_MARKER).isFile)

        assertEquals(AdoptOutcome.Adopted(DocumentState.OK), h.documents.adoptSealed(id))
        assertEquals(0, h.reader.reads.get())
        assertTrue(h.isOk(id))
    }

    @Test fun markSealedWithoutTheSealedFileFailsAndWritesNoMarker() = runTest {
        h.plainNote(id)
        val document = offMain { sealAsTheJobDoes() }
        File(h.paths.work(id), NoteFiles.SEALED).delete()
        val failure = runCatching { offMain { h.documents.markSealedBlocking(id, document) } }.exceptionOrNull()
        assertTrue("$failure", failure?.cause is IOException)
        assertFalse(File(h.paths.work(id), NoteFiles.SEALED_MARKER).exists())
    }

    @Test fun readBlockingGivesNullForAPlainNoteAndTheDocumentOtherwise() = runTest {
        h.plainNote(id)
        assertNull(offMain { h.documents.readBlocking(id) })
        val other = noteId(8)
        h.documentNote(other, transcript("a b c"))
        val document = offMain { h.documents.readBlocking(other) }
        assertEquals(DocumentState.OK, document?.documentState)
        // Reentrant under the note lock, as the seal step calls it.
        assertNotNull(offMain { h.locks.withNoteLockBlocking(other) { h.documents.readBlocking(other) } })
    }

    @Test fun blockingVoiceEntryPointsMatchTheSuspendOnes() = runTest {
        h.plainNote(id)
        val random = Random(3)
        val raw = FloatArray(ModelCatalog.VOICEPRINT_DIM) { random.nextFloat() - 0.5f }
        val embedding = requireNotNull(Voiceprints.normalised(raw))
        val print = SpeakerPrint(embedding, 12.0, null)
        assertTrue(offMain { voices.savePendingBlocking(id, mapOf("v1/S1" to print), 0) })
        val pending = h.paths.pendingPrints(id)
        assertTrue(pending.isFile)

        assertTrue(offMain { voices.discardPendingBlocking(id) })
        assertFalse(pending.exists())
        assertTrue(offMain { voices.printsBlocking(id, "v1") }.isEmpty())

        offMain { voices.savePendingBlocking(id, mapOf("v1/S1" to print), 0) }
        voices.adoptPending(id)
        val blocking = offMain { voices.printsBlocking(id, "v1") }
        assertEquals(setOf("S1"), blocking.keys)
        assertArrayEquals(embedding, blocking.getValue("S1").embedding, 1e-6f)
        assertEquals(voices.prints(id, "v1").keys, blocking.keys)
    }
}
