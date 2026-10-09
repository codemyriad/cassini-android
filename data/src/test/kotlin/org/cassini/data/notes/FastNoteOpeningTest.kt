package org.cassini.data.notes

import kotlinx.coroutines.test.runTest
import org.cassini.core.format.DocumentState
import org.cassini.data.DocumentSignature
import org.cassini.data.db.column
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.RandomAccessFile

/**
 * The FAST NOTE OPENING rule: a note whose document did not change opens from the cache rows without reading or
 * verifying the file; a changed signature derives the cache exactly once; a deleted cache is rebuilt. The reads are
 * counted through the DocumentReader seam.
 */
@RunWith(RobolectricTestRunner::class)
class FastNoteOpeningTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var h: NotesHarness

    private val words = transcript("allora vediamo la consegna di giovedì e poi decidiamo chi parla per primo")

    @org.junit.Before fun setUp() {
        h = NotesHarness(folder)
    }

    @After fun tearDown() = h.close()

    private fun wordRows(id: String) =
        h.db.column("SELECT COUNT(*) FROM words WHERE note_id = ?", id).single()!!.toInt()

    @Test fun aSecondOpenOfAnUnchangedDocumentDoesNotReadIt() = runTest {
        h.documentNote("a", words)

        assertEquals(RefreshOutcome.Refreshed(DocumentState.OK), h.notes.refreshIfStale("a"))
        assertEquals(1, h.reader.reads.get())
        val row = h.row("a")
        assertEquals(DocumentSignature.of(h.document("a")), row.documentSignature)
        assertEquals(DocumentState.OK, row.documentState)
        assertEquals(words.words.size, wordRows("a"))

        assertEquals(RefreshOutcome.Fresh, h.notes.refreshIfStale("a"))
        h.restart() // Process death: no document is remembered in memory.
        assertEquals(RefreshOutcome.Fresh, h.notes.refreshIfStale("a"))
        assertEquals(1, h.reader.reads.get())
        val shown = h.notes.words("a", "words", limit = 5).map { it.text }
        assertEquals(words.words.take(5).map { it.text }, shown)
    }

    @Test fun aChangedSignatureDerivesTheCacheExactlyOnce() = runTest {
        h.documentNote("a", words)
        h.notes.refreshIfStale("a")
        val before = h.row("a").documentSignature

        assertTrue(h.document("a").setLastModified(h.document("a").lastModified() + 10_000))
        h.restart()
        assertEquals(RefreshOutcome.Refreshed(DocumentState.OK), h.notes.refreshIfStale("a"))
        assertEquals(RefreshOutcome.Fresh, h.notes.refreshIfStale("a"))
        assertEquals(RefreshOutcome.Fresh, h.notes.refreshIfStale("a"))

        assertEquals(2, h.reader.reads.get())
        assertNotEquals(before, h.row("a").documentSignature)
    }

    @Test fun aSameSizeRewriteWithTheOldMtimeIsStillNoticed() = runTest {
        h.documentNote("a", words)
        h.notes.refreshIfStale("a")
        val file = h.document("a")
        val mtime = file.lastModified()
        RandomAccessFile(file, "rw").use { out ->
            out.seek(out.length() - 1)
            val last = out.read()
            out.seek(out.length() - 1)
            out.write(last xor 1)
        }
        assertTrue(file.setLastModified(mtime))

        h.restart()
        val outcome = h.notes.refreshIfStale("a")

        assertEquals(2, h.reader.reads.get())
        assertNotEquals(RefreshOutcome.Refreshed(DocumentState.OK), outcome)
        assertNotEquals(DocumentState.OK, h.row("a").documentState)
        assertEquals("the transcript stays readable", words.words.size, wordRows("a"))
    }

    @Test fun aDeletedWordCacheIsRebuilt() = runTest {
        h.documentNote("a", words)
        h.notes.refreshIfStale("a")
        h.db.cache().dropWords("a")
        assertEquals(0, wordRows("a"))

        h.restart()
        assertEquals(RefreshOutcome.Refreshed(DocumentState.OK), h.notes.refreshIfStale("a"))
        assertEquals(words.words.size, wordRows("a"))
        assertEquals(2, h.reader.reads.get())
        assertEquals(RefreshOutcome.Fresh, h.notes.refreshIfStale("a"))
    }

    @Test fun deletedVariantRowsAreRebuilt() = runTest {
        h.documentNote("a", words)
        h.notes.refreshIfStale("a")
        h.db.openHelper.writableDatabase.execSQL("DELETE FROM variants WHERE note_id = 'a'")

        assertEquals(RefreshOutcome.Refreshed(DocumentState.OK), h.notes.refreshIfStale("a"))
        assertEquals(listOf("words"), h.db.cache().variants("a").map { it.variantId })
        assertEquals(words.words.size, wordRows("a"))
    }

    @Test fun theCacheHoldsWhatTheNoteScreenShows() = runTest {
        h.documentNote("a", words, title = "Standup")
        h.notes.refreshIfStale("a")

        val variant = h.db.cache().variants("a").single()
        assertEquals(words.words.size, variant.wordCount)
        assertEquals(2, variant.speakerCount)
        assertTrue(variant.isDefault)
        val speakers = h.db.cache().speakers("a", "words")
        assertEquals(listOf("S1", "S2"), speakers.map { it.speakerId })
        assertEquals(listOf(1, 2), speakers.map { it.placeholderNumber })
        assertTrue(speakers.all { it.isPlaceholder })
        assertEquals(listOf(0L, 1_500L), speakers.map { it.firstMs })
        val body = h.db.column("SELECT body FROM note_text WHERE note_id = 'a'").single()
        assertEquals(words.words.joinToString(" ") { it.text }, body)
        assertEquals(words.words.joinToString(" ") { it.text }, h.row("a").preview)
        assertEquals(false, h.row("a").titleDirty)
    }
}
