package io.codemyriad.cassini.note

import io.codemyriad.cassini.core.export.ExportTranscript
import io.codemyriad.cassini.core.export.SrtWriter
import io.codemyriad.cassini.core.export.TextExport
import io.codemyriad.cassini.core.export.VttWriter
import io.codemyriad.cassini.core.format.Word
import io.codemyriad.cassini.data.db.SpeakerEntity
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
    fun markdownUsesItsOwnMimeAndSafeSuggestedNameAndSharesTranscriptEligibility() {
        val details = documentNote()
        val file = requireNotNull(NoteExports.file(details, io.codemyriad.cassini.ui.note.ExportKind.MARKDOWN))
        assertEquals("text/markdown", file.mime)
        assertEquals("Weekly sync.md", file.name)
        assertEquals(io.codemyriad.cassini.data.notes.TranscriptFormat.MARKDOWN, NoteExports.format(file.kind))
        assertEquals(io.codemyriad.cassini.ui.note.NoteAction.SAVE_MARKDOWN, NoteExports.row(file.kind))
        val unreadable = details.copy(
            note = details.note.copy(documentState = io.codemyriad.cassini.core.format.DocumentState.UNREADABLE),
        )
        assertEquals(null, NoteExports.file(unreadable, file.kind))
    }

    /** L-11 (D99): each subtitle format is its own row, so each shows its own progress. */
    @Test
    fun eachTextFormatHasItsOwnRow() {
        val rows = io.codemyriad.cassini.ui.note.ExportKind.entries.associateWith { NoteExports.row(it) }
        assertEquals(rows.size, rows.values.toSet().size)
        assertEquals(
            io.codemyriad.cassini.ui.note.NoteAction.SAVE_SRT,
            rows[io.codemyriad.cassini.ui.note.ExportKind.SRT],
        )
        assertEquals(
            io.codemyriad.cassini.ui.note.NoteAction.SAVE_VTT,
            rows[io.codemyriad.cassini.ui.note.ExportKind.VTT],
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

    @Test fun unassignedStoredAndRenamedLabelsWinForEveryFormatAndSelectedVersion() {
        val base = documentNote(variants = 2)
        val words = listOf(Word("unassigned", 0, 1000, "First"), Word("a", 200, 400, "Yes"))
        val copy = io.codemyriad.cassini.core.export.MarkdownCopy(
            "Speakers: {names}",
            "Transcript",
            "Notes",
            "(over {names})",
        ) {
            it.joinToString(" and ")
        }
        for (label in listOf("Voce sconosciuta", "Mira")) {
            for (version in listOf("v1", "v2")) {
                val expected = if (version == "v1") "Voce precedente" else label
                val details = base.copy(
                    note = base.note.copy(selectedVariant = version),
                    speakers = listOf(
                        SpeakerEntity(NOTE, "v1", "unassigned", 0, "Voce precedente", isPlaceholder = true),
                        SpeakerEntity(NOTE, "v2", "unassigned", 0, label, isPlaceholder = false),
                        SpeakerEntity(NOTE, version, "a", 1, "Ada", isPlaceholder = false),
                    ),
                )
                val labels = NoteExports.labels(details, english)
                assertEquals(expected, labels["unassigned"])
                val transcript = ExportTranscript("Meeting", 1000, words, labels)
                val texts = listOf(
                    io.codemyriad.cassini.core.export.MarkdownExport.write(transcript, "", copy),
                    TextExport.write(transcript, "", ""),
                    SrtWriter.write(transcript),
                    VttWriter.write(transcript),
                )
                for (text in texts) {
                    assertTrue(text, expected in text)
                    assertFalse(text, "Speaker 1" in text || "unassigned" in text)
                }
            }
        }
    }
}
