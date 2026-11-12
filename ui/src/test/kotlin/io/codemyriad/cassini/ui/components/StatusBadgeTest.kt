package io.codemyriad.cassini.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import io.codemyriad.cassini.ui.format.DayLabel
import io.codemyriad.cassini.ui.theme.CassiniTheme
import io.codemyriad.cassini.ui.theme.CassiniTypography
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class StatusBadgeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun everyKindKeepsItsGlyphBeforeTheLabelAndUsesTabularSemiboldType() {
        var kind by mutableStateOf(StatusBadgeKind.Recording)
        rule.setContent { CassiniTheme { StatusChip(kind.name, kind) } }
        for (next in StatusBadgeKind.entries) {
            rule.runOnIdle { kind = next }
            val glyph = rule.onNodeWithTag("badge-glyph-$next", useUnmergedTree = true).getBoundsInRoot()
            val label = rule.onNodeWithText(next.name, useUnmergedTree = true).getBoundsInRoot()
            assertTrue("$next glyph has visible dimensions", glyph.right > glyph.left && glyph.bottom > glyph.top)
            assertTrue("$next glyph precedes label", glyph.right <= label.left)
        }
        assertEquals("tnum", CassiniTypography.chip.fontFeatureSettings)
        assertEquals(600, CassiniTypography.chip.fontWeight!!.weight)
        assertEquals(12f, CassiniTypography.chip.fontSize.value)
    }

    @Test fun spokenEtaUsesTheSharedSingularAndPluralForms() {
        var minutes by mutableStateOf(1L)
        rule.setContent {
            CassiniTheme {
                NoteRow(
                    NoteRowUi(
                        id = "running",
                        title = "Meeting",
                        durationMs = 48_000,
                        time = LocalTime.of(10, 2),
                        status = NoteStatusUi.Transcribing,
                        transcriptionMinutes = minutes,
                    ),
                    DayLabel.Today,
                    onAction = {},
                )
            }
        }
        rule.onNodeWithContentDescription("transcribing, about 1 minute left", substring = true).assertExists()
        rule.runOnIdle { minutes = 4 }
        rule.onNodeWithContentDescription("transcribing, about 4 minutes left", substring = true).assertExists()
    }

    @Test fun longSpeakerMetadataEllipsizesOnTheBadgeLine() {
        val name = "A long stored speaker label with punctuation, kept verbatim in accessibility"
        val row = NoteRowUi(
            id = "running",
            title = "Meeting",
            durationMs = 48_000,
            time = LocalTime.of(10, 2),
            status = NoteStatusUi.Transcribing,
            transcriptionMinutes = 4,
            speakerLabels = persistentListOf(RowSpeakerUi(name, 1)),
        )
        rule.setContent { CassiniTheme { NoteRow(row, DayLabel.Today, onAction = {}) } }
        val glyph = rule.onNodeWithTag("badge-glyph-Transcribing", useUnmergedTree = true).getBoundsInRoot()
        val text = rule.onNodeWithText(name, useUnmergedTree = true)
        val bounds = text.getBoundsInRoot()
        assertTrue("Names follow the badge", glyph.right <= bounds.left)
        val glyphMiddle = (glyph.top.value + glyph.bottom.value) / 2
        val namesMiddle = (bounds.top.value + bounds.bottom.value) / 2
        assertEquals("Metadata stays on the badge line", glyphMiddle, namesMiddle, 1f)
        val layouts = mutableListOf<TextLayoutResult>()
        text.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertEquals(1, layouts.single().lineCount)
        assertTrue("Long names have an ellipsis", layouts.single().isLineEllipsized(0))
    }
}
