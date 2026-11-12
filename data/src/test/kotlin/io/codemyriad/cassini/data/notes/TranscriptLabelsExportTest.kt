package io.codemyriad.cassini.data.notes

import android.net.Uri
import io.codemyriad.cassini.core.export.MarkdownCopy
import io.codemyriad.cassini.core.export.MarkdownOptions
import io.codemyriad.cassini.core.format.CassiniDocument
import io.codemyriad.cassini.core.format.Transcript
import io.codemyriad.cassini.core.format.Word
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class TranscriptLabelsExportTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var h: NotesHarness

    @Before fun setUp() { h = NotesHarness(folder) }

    @After fun tearDown() = h.close()

    @Test fun storedUnassignedLabelAndRenameSurviveEveryExportAndAnOlderVersion() = runTest {
        val before = words("Before")
        h.documentNote("labels", before)
        val firstBytes = CassiniDocument.create(
            fixtureOpus.readBytes(),
            before,
            "Labels",
            JSONObject(),
            speakerLabels = mapOf("unassigned" to "Voce sconosciuta", "ada" to "Ada"),
        )
        val firstDocument = CassiniDocument.read(firstBytes)
        val both = CassiniDocument.create(firstBytes, words("After"), "Labels", JSONObject(), firstDocument)
        h.document("labels").writeBytes(both)
        h.documents.refreshIfStale("labels")
        for (label in listOf("Voce sconosciuta", "Mira")) {
            if (label == "Mira") {
                val outcome = h.documents.relabel("labels", mapOf("unassigned" to label))
                assertTrue(outcome is EditOutcome.Done)
            }
            val details = requireNotNull(h.notes.note("labels").first())
            assertEquals(2, details.variants.size)
            val original = h.document("labels").readBytes()
            for (version in details.variants) {
                val labels = details.speakers.filter { it.variantId == version.variantId }.associate { it.speakerId to it.label }
                assertEquals(label, labels["unassigned"])
                val expected = if (version.variantId == firstDocument.defaultId) "Before" else "After"
                exportVersion(version.variantId, labels, expected)
            }
            assertEquals(details, h.notes.note("labels").first())
            assertArrayEquals(original, h.document("labels").readBytes())
        }
    }

    private fun words(text: String) = Transcript(
        listOf(Word("unassigned", 0, 500, text), Word("ada", 600, 900, "Yes")),
        "it",
    )

    private suspend fun exportVersion(version: String, labels: Map<String, String>, word: String) {
        val copy = MarkdownCopy("Speakers: {names}", "Transcript", "Notes", "(over {names})") {
            it.joinToString(" and ")
        }
        for (format in TranscriptFormat.entries) {
            val request = TranscriptExport(format, labels, variantId = version, markdown = MarkdownOptions("", copy))
            val dest = File(folder.root, "labels-$version.${format.extension}")
            val outcome = h.documents.exportTranscript("labels", request, Uri.fromFile(dest))
            assertTrue(outcome is ExportOutcome.Saved)
            val text = dest.readText()
            assertTrue(text, labels.getValue("unassigned") in text)
            assertTrue(text, word in text)
            assertFalse(text, "unassigned" in text || "Speaker 1" in text)
        }
    }
}
