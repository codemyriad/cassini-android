package org.cassini.core.library

import org.cassini.core.testing.FixtureTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The catalogue files of the committed filesDir trees, read as 0.1.0 reads them. */
class LegacyLibraryJsonTest {
    private fun text(tree: String, name: String) = File(FixtureTree.directory(), "$tree/$name").readText()

    @Test fun theZeroOneZeroLibraryReadsNewestFirst() {
        val notes = LegacyLibraryJson.library(text("v0.1.0-basic", "library.json"))
        assertEquals(3, notes.size)
        assertEquals(notes.sortedByDescending { it.createdAt }, notes)
        assertTrue(notes.all { it.session.libraryId == it.id })
        val paused = notes.single { it.session.processingPaused }
        assertNull(paused.session.document)
        assertTrue(paused.session.transcript!!.words.isNotEmpty())
        val sealed = notes.single { it.session.document != null }
        assertEquals("words", sealed.session.selectedVariant)
        assertNull("A sealed note keeps no inline transcript", sealed.session.transcript)
    }

    @Test fun theZeroOneZeroSessionNamesTheShownNote() {
        val session = LegacyLibraryJson.session(text("v0.1.0-basic", "session.json"))
        val notes = LegacyLibraryJson.library(text("v0.1.0-basic", "library.json"))
        assertEquals(notes.single { it.id == session.libraryId }.session.uri, session.uri)
        assertTrue(session.screen!!.isNotEmpty())
    }

    @Test fun zeroZeroSixShapesReadWithTheirRetiredMembersIgnored() {
        val notes = LegacyLibraryJson.library(text("v0.0.6-beta", "library.json"))
        assertEquals(2, notes.size)
        assertTrue(notes.all { it.session.document == null && !it.session.processingPaused })
        val session = LegacyLibraryJson.session(text("v0.0.6-beta", "session.json"))
        assertEquals("FP32", session.resultPrecision)
        assertNull("An empty document reads as none", session.document)
        assertNull(session.selectedVariant)
        assertEquals("it", session.transcript!!.language)
    }

    @Test fun theZeroZeroFourSessionCarriesItsTranscript() {
        val session = LegacyLibraryJson.session(text("v0.0.4-style", "session.json"))
        assertTrue(session.transcript!!.words.isNotEmpty())
        assertEquals("FP32", session.resultPrecision)
        assertNull(session.document)
    }

    @Test fun anotherLibraryVersionIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { LegacyLibraryJson.library("""{"version":2,"notes":[]}""") }
    }
}
