package io.codemyriad.cassini.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import io.codemyriad.cassini.ui.theme.CassiniTheme
import io.codemyriad.cassini.ui.theme.CassiniTypography
import io.codemyriad.cassini.ui.theme.DarkColors
import io.codemyriad.cassini.ui.theme.LightColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS-w390dp-h844dp-xhdpi")
class Phase1BadgeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun everyKindHasAPillAndGlyphWithSharedMetrics() {
        var kind by mutableStateOf(StatusBadgeKind.Recording)
        rule.setContent { CassiniTheme { StatusChip(kind.name, kind, Modifier.testTag("pill")) } }
        for (value in StatusBadgeKind.entries) {
            rule.runOnIdle { kind = value }
            val pill = rule.onNodeWithTag("pill").getBoundsInRoot()
            assertEquals(22f, (pill.bottom - pill.top).value)
            val glyph = rule.onNodeWithTag("badge-glyph-$value", useUnmergedTree = true).getBoundsInRoot()
            assertTrue(glyph.right > glyph.left && glyph.bottom > glyph.top)
        }
        assertEquals(12f, CassiniTypography.chip.fontSize.value)
        assertEquals(600, CassiniTypography.chip.fontWeight!!.weight)
        assertEquals("tnum", CassiniTypography.chip.fontFeatureSettings)
    }

    @Test fun transcribingSpinnerUsesAccentInBothModes() {
        var dark by mutableStateOf(false)
        rule.setContent {
            CassiniTheme(darkTheme = dark) {
                Box(Modifier.background(CassiniTheme.colors.surface)) {
                    StatusChip("Transcribing", StatusBadgeKind.Transcribing)
                }
            }
        }
        for (value in listOf(false, true)) {
            rule.runOnIdle { dark = value }
            val colors = if (value) DarkColors else LightColors
            val glyph = rule.onNodeWithTag("badge-glyph-Transcribing", useUnmergedTree = true)
            val pixels = glyph.captureToImage().toPixelMap()
            val hasAccent = (0 until pixels.width).any { x ->
                (0 until pixels.height).any { y -> pixels[x, y].toArgb() == colors.accent.toArgb() }
            }
            assertTrue("Transcribing spinner must contain opaque accent pixels ($value)", hasAccent)
        }
    }

    @Test
    @Config(qualifiers = "en-rUS", fontScale = 1.5f)
    fun largeEnglishLabelsFit() = largeLabelsFit()

    @Test
    @Config(qualifiers = "it-rIT", fontScale = 1.5f)
    fun largeItalianLabelsFit() = largeLabelsFit()

    @Test
    @Config(qualifiers = "fr-rFR", fontScale = 1.5f)
    fun largeFrenchLabelsFit() = largeLabelsFit()

    @Test
    @Config(qualifiers = "es-rES", fontScale = 1.5f)
    fun largeSpanishLabelsFit() = largeLabelsFit()

    @Test
    @Config(qualifiers = "pt-rBR", fontScale = 1.5f)
    fun largeBrazilianPortugueseLabelsFit() = largeLabelsFit()

    @Test
    @Config(qualifiers = "pt-rPT", fontScale = 1.5f)
    fun largeEuropeanPortugueseLabelsFit() = largeLabelsFit()

    private fun largeLabelsFit() {
        var kind by mutableStateOf(StatusBadgeKind.Transcribing)
        var label = ""
        rule.setContent {
            CassiniTheme {
                label = statusBadgeText(kind, minutes = 0, positionMs = 60_000)
                Box(Modifier.width(144.dp)) { StatusChip(label, kind) }
            }
        }
        for (value in listOf(StatusBadgeKind.Transcribing, StatusBadgeKind.Paused, StatusBadgeKind.Unreadable)) {
            rule.runOnIdle { kind = value }
            rule.waitForIdle()
            val layouts = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText(label).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult]
                .action!!.invoke(layouts)
            val layout = layouts.single()
            assertTrue("Full catalog label must fit: $label", !layout.multiParagraph.didExceedMaxLines)
            for (line in 0 until layout.lineCount) {
                assertTrue(label, layout.getLineRight(line) - layout.getLineLeft(line) <= layout.size.width + 1)
            }
        }
    }
}
