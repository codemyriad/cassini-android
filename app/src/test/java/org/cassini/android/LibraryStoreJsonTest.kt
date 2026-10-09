package org.cassini.android

import org.cassini.core.format.Transcript
import org.cassini.core.format.Word
import org.cassini.core.library.LibraryNote
import org.cassini.core.library.Session
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The `library.json` note writer and reader. */
class LibraryStoreJsonTest {
    private val transcript = Transcript(listOf(Word("spk_1", 0, 100, "Ciao"), Word("spk_1", 100, 300, "mondo.")), "it")

    @Test fun catalogueRetainsPlaybackAndLegacyWordsButUsesDocumentOnReopen() {
        val raw = LibraryNote.update(
            null,
            Session(
                uri = "file:///raw.wav",
                name = "Raw",
                transcript = transcript,
                positionMs = 200,
                inferenceMs = 99
            ),
            "one",
            100
        )
        val restored = libraryNote(JSONObject(raw.json().toString()))
        assertEquals(raw, restored)
        val portable = LibraryNote.update(
            raw,
            restored.session.copy(document = "/note.opus", selectedVariant = "first"),
            "one",
            200
        )
        val reopened = libraryNote(JSONObject(portable.json().toString()))
        assertNull(reopened.session.transcript)
        assertEquals("Ciao mondo.", reopened.text)
        assertEquals(200, reopened.session.positionMs)
        assertEquals("first", reopened.session.selectedVariant)
        assertEquals("Ciao mondo.", LibraryNote.update(reopened, reopened.session, "one", 300).text)
        assertEquals(
            "",
            LibraryNote.update(reopened, reopened.session.copy(selectedVariant = "unsupported"), "one", 300).text
        )
    }
}
