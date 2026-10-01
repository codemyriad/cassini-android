package org.cassini.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LibraryNoteTest {
    private val transcript = Transcript(listOf(Word("spk_1", 0, 100, "Ciao"), Word("spk_1", 100, 300, "mondo.")), "it")

    @Test fun conversionAndRetranscriptionKeepOneIdentityAndOriginalDate() {
        val raw = LibraryNote.update(null, Session(uri = "file:///note.m4a", name = "Morning"), "one", 100)
        val portable = LibraryNote.update(raw, raw.session.copy(uri = "file:///note.opus", document = "/note.opus", transcript = transcript), "one", 200)
        val next = LibraryNote.update(portable, portable.session.copy(uri = "file:///updated.opus", document = "/updated.opus",
            selectedVariant = "second", transcript = transcript.copy(words = listOf(Word("spk_1", 0, 200, "Benvenuti.")))), "one", 300)
        assertEquals("one", next.id)
        assertEquals("one", next.session.libraryId)
        assertEquals(100L, next.createdAt)
        assertEquals("Benvenuti.", next.text)
    }

    @Test fun searchCoversTitleAndWordsBeyondTheCardPreview() {
        val note = LibraryNote("one", 100, Session(name = "Morning meeting"), "Opening remarks. ".repeat(30) + "Choose the teal version.")
        assertTrue(note.matches("MORNING"))
        assertTrue(note.matches(" teal "))
        assertFalse(note.matches("orange"))
        assertTrue(note.matches(""))
        assertTrue(note.snippet("teal").contains("teal"))
        assertTrue(note.snippet("teal").startsWith("…"))
    }

    @Test fun recreatedViewerReturnsToItsOwnNote() {
        fun note(id: String, uri: String, position: Int = 0) = LibraryNote(id, 100, Session(uri = uri, name = id, libraryId = id, positionMs = position), "")
        val moved = note("n", "file:///b.opus", 7)
        val reopened = note("m", "file:///a.opus")
        val notes = listOf(moved, reopened)
        // An import or retranscription that finished after state was saved: this viewer wrote session.json last.
        val mine = Session(uri = "file:///c.opus", libraryId = "c", screen = "one")
        assertEquals(mine, LibraryNote.shown("one", "n", "file:///a.opus", mine, notes))
        assertEquals(mine, LibraryNote.shown("one", null, null, mine, notes))
        assertNull("An empty viewer has no note to return to", LibraryNote.shown("one", null, null, Session(screen = "one"), notes))
        assertNull(LibraryNote.shown("one", null, null, mine.copy(screen = "two"), notes))
        // Another viewer wrote session.json: the note ID follows a moved note, even if that viewer shows the original file.
        val other = reopened.session.copy(screen = "two")
        assertEquals(moved.session, LibraryNote.shown("one", "n", "file:///a.opus", other, notes))
        assertNull("A file match must not replace a different note", LibraryNote.shown("one", "gone", "file:///a.opus", other, notes))
        // Without a catalogue entry the file reference is all there is.
        assertEquals(reopened.session, LibraryNote.shown("one", null, "file:///a.opus", Session(screen = "two"), notes))
        val uncatalogued = Session(uri = "file:///d.input", screen = "two")
        assertEquals(uncatalogued, LibraryNote.shown("one", null, "file:///d.input", uncatalogued, emptyList()))
        assertEquals(other, LibraryNote.shown("one", "m", "file:///a.opus", other, emptyList()))
        // An unreadable catalogue: another viewer's session.json still follows the note to its new file.
        val elsewhere = moved.session.copy(screen = "two")
        assertEquals(elsewhere, LibraryNote.shown("one", "n", "file:///a.opus", elsewhere, emptyList()))
    }

    @Test fun catalogueRetainsPlaybackAndLegacyWordsButUsesDocumentOnReopen() {
        val raw = LibraryNote.update(null, Session(uri = "file:///raw.wav", name = "Raw", transcript = transcript,
            positionMs = 200, inferenceMs = 99), "one", 100)
        val restored = LibraryNote.fromJson(JSONObject(raw.json().toString()))
        assertEquals(raw, restored)
        val portable = LibraryNote.update(raw, restored.session.copy(document = "/note.opus", selectedVariant = "first"), "one", 200)
        val reopened = LibraryNote.fromJson(JSONObject(portable.json().toString()))
        assertNull(reopened.session.transcript)
        assertEquals("Ciao mondo.", reopened.text)
        assertEquals(200, reopened.session.positionMs)
        assertEquals("first", reopened.session.selectedVariant)
        assertEquals("Ciao mondo.", LibraryNote.update(reopened, reopened.session, "one", 300).text)
        assertEquals("", LibraryNote.update(reopened, reopened.session.copy(selectedVariant = "unsupported"), "one", 300).text)
    }
}
