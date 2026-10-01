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
