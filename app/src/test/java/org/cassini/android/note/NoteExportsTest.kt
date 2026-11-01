package org.cassini.android.note

import org.cassini.core.export.ExportTranscript
import org.cassini.core.export.SrtWriter
import org.cassini.core.export.TextExport
import org.cassini.core.export.VttWriter
import org.cassini.core.format.Word
import org.cassini.data.db.SpeakerEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Speaker labels in the TXT, SRT and VTT exports: what the file stores, verbatim (D47), with the D53.3 fallback. */
class NoteExportsTest {
    /** The English UI's placeholder, as the app passes it. */
    private val english: (Int) -> String = { "Speaker $it" }

    /** A note an Italian writer transcribed: "Voce 2" is a stored placeholder, Giulia a name. */
    private val italian = documentNote().let { details ->
        details.copy(
            speakers = listOf(
                SPEAKERS[0],
                SpeakerEntity(NOTE, "v1", "b", 1, "Voce 2", isPlaceholder = true, placeholderNumber = 2),
                SPEAKERS[2],
            ),
        )
    }

    @Test
    fun aStoredPlaceholderIsExportedVerbatimUnderAnotherUiLanguage() {
        val labels = NoteExports.labels(italian, english)
        assertEquals(mapOf("a" to "Giulia", "b" to "Voce 2", "c" to "Paolo"), labels)

        val words = WORDS.map { Word(it.speaker, it.startMs, it.endMs, it.text) }
        val transcript = ExportTranscript("Weekly sync", 4_000, words, labels)
        val exports = listOf(
            "TXT" to TextExport.write(transcript, "", ""),
            "SRT" to SrtWriter.write(transcript),
            "VTT" to VttWriter.write(transcript),
        )
        for ((format, text) in exports) {
            assertTrue("$format carries the stored label:\n$text", "Voce 2" in text)
            assertFalse("$format never re-localises it:\n$text", "Speaker 2" in text)
        }
    }

    @Test
    fun aSpeakerWithoutAStoredLabelIsExportedAsSpeakerNNeverItsId() {
        val unlabelled = italian.copy(
            speakers = listOf(
                SPEAKERS[0],
                SpeakerEntity(NOTE, "v1", "b", 1, "b", isPlaceholder = true, placeholderNumber = 2),
                SpeakerEntity(NOTE, "v1", "c", 2, "", isPlaceholder = true),
            ),
        )
        val expected = mapOf("a" to "Giulia", "b" to "Speaker 2", "c" to "Speaker 3")
        assertEquals(expected, NoteExports.labels(unlabelled, english))
    }
}
