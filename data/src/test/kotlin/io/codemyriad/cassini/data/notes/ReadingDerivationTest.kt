package io.codemyriad.cassini.data.notes

import io.codemyriad.cassini.core.format.CassiniDocument
import io.codemyriad.cassini.core.format.DocumentState
import io.codemyriad.cassini.core.format.Transcript
import io.codemyriad.cassini.core.format.Word
import io.codemyriad.cassini.core.library.AudioKind
import io.codemyriad.cassini.core.library.Origin
import io.codemyriad.cassini.data.db.NoteEntity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure cache derivation: no Room database, Android runner, audio or device. */
class ReadingDerivationTest {
    @Test fun searchBodyAndPreviewUseTheSameReadingProjection() {
        val words = fixture("acknowledgment")
        val cache = DocumentDerivation.derive(row, document(words), "signature", 1)
        assertTrue(cache.body.contains("(Ben: Sounds good.) and I will"))
        assertTrue(cache.note.preview.orEmpty().contains("(Ben: Sounds good.)"))
        assertEquals(words.map { it.text }, cache.words.map { it.text })
        assertEquals(words.map { it.endMs }, cache.words.map { it.endMs })
        assertEquals(listOf(1, 1), cache.speakers.map { it.turnCount })
        val competing = DocumentDerivation.derive(row, document(fixture("competing")), "signature", 1)
        assertTrue(competing.body.contains("Linux build"))
        assertTrue(competing.body.contains("microphone settings."))
        assertEquals(listOf(1, 1), competing.speakers.map { it.turnCount })
    }

    @Test fun measuredEndsAreCachedAndUsedByStatistics() {
        val words = listOf(Word("ada", 0, 4000, "held."))
        val legacy = DocumentDerivation.derive(row, document(words), "signature", 1)
        val measured = DocumentDerivation.derive(row, document(words, true), "signature", 1)
        assertFalse(legacy.variants.single().wordEndsBoundedByAudio)
        assertTrue(measured.variants.single().wordEndsBoundedByAudio)
        assertEquals(1000L, legacy.speakers.single().sampleEndMs)
        assertEquals(4000L, measured.speakers.single().sampleEndMs)
        assertEquals(4000L, legacy.words.single().endMs)
        assertEquals(4000L, measured.words.single().endMs)
    }

    @Test fun onlyLiteralTrueAttestsWordEndMeasurements() {
        for (value in listOf<Any>("true", 1, false, JSONObject.NULL)) {
            val doc = document(emptyList())
            val timing = JSONObject().put("endsBoundedByAudio", value)
            doc.manifest!!.put("provenance", JSONObject().put("wordTimings", timing))
            assertFalse(DocumentDerivation.wordEndsBoundedByAudio(doc))
        }
    }

    @Test fun storedColourSlotsAreCachedWithoutChangingLabelsOrOrdinals() {
        val doc = document(fixture("acknowledgment"))
        doc.manifest!!.getJSONArray("speakers").getJSONObject(0).put("colourIndex", 5)
        val cache = DocumentDerivation.derive(row, doc, "signature", 1)
        val ada = cache.speakers.single { it.speakerId == "ada" }
        assertEquals(5L, ada.colourIndex)
        assertEquals(0, ada.ordinal)
        assertEquals("Ada", ada.label)
        assertFalse(ada.isPlaceholder)
    }

    @Test fun storedSlotsAndExplicitUnnamedProvenanceReachTheCache() {
        val doc = document(fixture("acknowledgment"))
        val speakers = doc.manifest!!.getJSONArray("speakers")
        speakers.getJSONObject(0).put("colourIndex", 5).put("provenance", "unnamed")
        val cache = DocumentDerivation.derive(row, doc, "signature", 1)
        val ada = cache.speakers.single { it.speakerId == "ada" }
        assertEquals(5L, ada.colourIndex)
        assertTrue(ada.isPlaceholder)
    }

    private fun document(words: List<Word>, bounded: Boolean = false): CassiniDocument {
        val speakers = JSONArray()
            .put(JSONObject().put("id", "ada").put("label", "Ada"))
            .put(JSONObject().put("id", "ben").put("label", "Ben"))
        val manifest = JSONObject().put("speakers", speakers)
            .put("provenance", JSONObject().put("wordTimings", JSONObject().put("endsBoundedByAudio", bounded)))
        return CassiniDocument(
            DocumentState.OK.code,
            manifest,
            listOf(CassiniDocument.Variant("v", JSONObject().put("default", true), null, Transcript(words))),
        )
    }

    private fun fixture(name: String): List<Word> = Transcript.fromJson(
        java.io.File(checkNotNull(System.getProperty("cassini.interjections")), "$name.words.json").readText(),
    ).words

    private val row = NoteEntity(
        id = "note",
        title = "Test",
        createdAt = 0,
        addedAt = 0,
        updatedAt = 0,
        origin = Origin.IMPORTED_DOCUMENT,
        audioFile = "document.opus",
        audioKind = AudioKind.DOCUMENT,
    )
}
