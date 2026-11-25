package io.codemyriad.cassini.data.notes

import android.net.Uri
import io.codemyriad.cassini.core.format.backgroundSpeakers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Background voices (cassini-ux D112 / L-86) through the document repository, the catalogue and the exports. */
@RunWith(RobolectricTestRunner::class)
class BackgroundVoiceRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var h: NotesHarness
    private val first = transcript("good morning everyone today we discuss the delivery on Thursday")

    @Before fun setUp() {
        h = NotesHarness(folder)
    }

    @After fun tearDown() = h.close()

    /** D112 / L-86: a background voice is stored in the file and left out of search, the preview and the exports. */
    @Test fun aBackgroundVoiceIsStoredAndLeftOutOfSearchAndTextExports() = runTest {
        h.documentNote("a", first)
        h.notes.refreshIfStale("a")

        assertEquals(EditOutcome.Done(), h.documents.relabel("a", mapOf("S1" to "Giulia"), mapOf("S2" to true)))

        val document = h.readFile("a")
        assertEquals(setOf("S2"), document.backgroundSpeakers)
        assertEquals("S2", document.speakerLabel("S2"))
        assertEquals("the transcript body is unchanged", first.words, document.selected(null)!!.transcript!!.words)
        val speakers = h.db.cache().speakers("a", "words")
        assertEquals(listOf(false, true), speakers.map { it.background })
        val body = h.db.notes().text("a")!!.body
        assertTrue(body.contains("delivery"))
        assertFalse("search leaves the background voice out", body.contains("discuss"))
        assertFalse(body.contains("Thursday"))

        val dest = folder.newFile("export.txt")
        val labels = mapOf("S1" to "Giulia", "S2" to "S2")
        val request = TranscriptExport(TranscriptFormat.TXT, labels, emptyText = "No words")
        assertTrue(h.documents.exportTranscript("a", request, Uri.fromFile(dest)) is ExportOutcome.Saved)
        val text = dest.readText()
        assertTrue(text.contains("delivery"))
        assertFalse("text exports leave the background voice out", text.contains("discuss"))

        // Switching it off brings everything back.
        assertEquals(EditOutcome.Done(), h.documents.relabel("a", emptyMap(), mapOf("S2" to false)))
        assertEquals(emptySet<String>(), h.readFile("a").backgroundSpeakers)
        assertTrue(h.db.notes().text("a")!!.body.contains("discuss"))
        assertTrue(h.documents.relabel("a", emptyMap(), mapOf("S9" to true)) is EditOutcome.Invalid)
    }
}
