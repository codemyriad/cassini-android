package io.codemyriad.cassini.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import io.codemyriad.cassini.ui.format.DayLabel
import io.codemyriad.cassini.ui.note.NoteSubtitleUi
import io.codemyriad.cassini.ui.note.ProvenanceUi
import io.codemyriad.cassini.ui.note.subtitleText
import io.codemyriad.cassini.ui.theme.CassiniTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalTime

/** L-10 (cassini-ux D99): the Imported provenance on a phone row and in the Meeting header subtitle. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS-w390dp-h844dp-xhdpi")
class ImportedProvenanceTest {
    @get:Rule val rule = createComposeRule()

    private fun row(status: NoteStatusUi, imported: Boolean) = NoteRowUi(
        id = "n",
        title = "Supplier call",
        durationMs = 1_430_000,
        time = LocalTime.of(15, 10),
        status = status,
        imported = imported,
    )

    @Test fun aFinishedImportedRowShowsTheOutlineImportedBadgeAndSaysIt() {
        var state by mutableStateOf(row(NoteStatusUi.Transcribed(speakers = 3, words = 40), imported = true))
        rule.setContent {
            CassiniTheme { NoteRow(state, DayLabel.Today, onAction = {}, modifier = Modifier.testTag("row")) }
        }
        rule.onAllNodesWithTag("badge-glyph-Received", useUnmergedTree = true).assertCountEquals(1)
        assertTrue(description().contains("imported"))
        // Any progress or problem badge takes its place while it applies (STATUS-BADGES.md, L-10).
        for (status in listOf(NoteStatusUi.Transcribing, NoteStatusUi.AudioOnly, NoteStatusUi.Failed)) {
            rule.runOnIdle { state = row(status, imported = true) }
            rule.onAllNodesWithTag("badge-glyph-Received", useUnmergedTree = true).assertCountEquals(0)
            assertFalse(description().contains("imported"))
        }
        // A note recorded on this device has no provenance badge.
        rule.runOnIdle { state = row(NoteStatusUi.Transcribed(speakers = 3, words = 40), imported = false) }
        rule.onAllNodesWithTag("badge-glyph-Received", useUnmergedTree = true).assertCountEquals(0)
        assertFalse(description().contains("imported"))
    }

    @Test fun theHeaderSubtitleEndsWithTheCatalogProvenance() {
        // The clock follows the device (12-hour under en-US); the provenance words follow the plain subtitle.
        val plain = subtitle(null)
        assertTrue(plain, plain.startsWith("Today, ") && plain.endsWith(" · 48:13"))
        assertEquals("$plain · Imported", subtitle(ProvenanceUi.IMPORTED))
        assertEquals("$plain · Imported · Transcribed on another device", subtitle(ProvenanceUi.IMPORTED_OTHER_DEVICE))
        assertEquals("Date unknown · 48:13 · Imported", subtitle(ProvenanceUi.IMPORTED, DayLabel.Unknown))
    }

    @Test
    @Config(qualifiers = "it-rIT")
    fun theItalianSubtitleUsesTheCatalogWords() {
        assertTrue(subtitle(ProvenanceUi.IMPORTED).endsWith(" · Importata"))
        val otherDevice = subtitle(ProvenanceUi.IMPORTED_OTHER_DEVICE)
        assertTrue(otherDevice, otherDevice.endsWith(" · Importata · Trascritta su un altro dispositivo"))
    }

    private fun description(): String =
        rule.onNodeWithTag("row").fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()

    private var shown by mutableStateOf<NoteSubtitleUi?>(null)
    private var text = ""
    private var started = false

    /** One composition per test (the rule allows one setContent); each call swaps the subtitle it shows. */
    private fun subtitle(provenance: ProvenanceUi?, day: DayLabel = DayLabel.Today): String {
        val next = NoteSubtitleUi(day, LocalTime.of(10, 2), 2_893_000, provenance = provenance)
        if (started) {
            rule.runOnIdle { shown = next }
        } else {
            shown = next
            started = true
            rule.setContent { CassiniTheme { shown?.let { text = subtitleText(it) } } }
        }
        rule.waitForIdle()
        return text
    }
}
