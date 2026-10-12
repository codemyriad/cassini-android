package org.cassini.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.cassini.ui.format.DayLabel
import org.cassini.ui.format.FIXED_ZONE
import org.cassini.ui.format.ProvideFixedFormat
import org.cassini.ui.theme.CassiniTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/**
 * The fixes for font scale 2.0 from the P12 device review (docs/ui-fidelity.md): the Library row suffix goes to the
 * next line and is not cut ("· No speech" showed as "· No"), and the "10" of the skip buttons keeps its size. Native
 * graphics give the text its real width.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class LargeFontScaleTest {
    @get:Rule val rule = createComposeRule()

    private fun setThemed(fontScale: Float, content: @Composable () -> Unit) {
        rule.setContent {
            CassiniTheme(darkTheme = false) {
                ProvideFixedFormat(locale = Locale.ENGLISH, is24Hour = true, zone = FIXED_ZONE) {
                    ScaledFont(fontScale, content)
                }
            }
        }
    }

    @Composable
    private fun ScaledFont(fontScale: Float, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale), content = content)
    }

    @Test fun theRowSuffixIsNotCutAtFontScale2() {
        val silent = ComponentFixtures.otherRows.first { it.first.id == "silent" }.first
        setThemed(fontScale = 2f) {
            Column(Modifier.width(390.dp).testTag(LIST)) {
                NoteRow(ComponentFixtures.board, DayLabel.Yesterday, onAction = {})
                NoteRow(silent, DayLabel.Yesterday, onAction = {})
            }
        }
        val list = rule.onNode(hasTestTag(LIST)).getUnclippedBoundsInRoot()
        for (suffix in listOf("· Resume", "· No speech")) {
            val node = rule.onNode(hasText(suffix), useUnmergedTree = true)
            // The first line holds the whole suffix, and the row gives it the width that line needs (px).
            val layout = node.textLayout()
            val shown = suffix.substring(0, layout.getLineEnd(0))
            assertEquals("The first line of \"$suffix\"", suffix, shown)
            val needs = layout.getLineRight(0) - layout.getLineLeft(0)
            val given = node.fetchSemanticsNode().size.width
            assertTrue("\"$suffix\" needs $needs px and has $given px", needs <= given)
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue("\"$suffix\" ends at ${bounds.right}, past the row (${list.right})", bounds.right <= list.right)
        }
    }

    @Test fun theSkipDigitsKeepTheirSizeAtFontScale2() {
        setThemed(fontScale = 1f) {
            Row {
                SkipButton(forward = false, onClick = {}, modifier = Modifier.testTag(NORMAL))
                ScaledFont(2f) { SkipButton(forward = false, onClick = {}, modifier = Modifier.testTag(LARGE)) }
            }
        }
        // The button hides its text from semantics, so the test compares the pixels of the two buttons.
        val normal = rule.onNode(hasTestTag(NORMAL)).captureToImage().asAndroidBitmap()
        val large = rule.onNode(hasTestTag(LARGE)).captureToImage().asAndroidBitmap()
        assertTrue("The skip button at font scale 2.0 differs from 1.0", normal.sameAs(large))
    }

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    private companion object {
        const val LIST = "list"
        const val NORMAL = "normal"
        const val LARGE = "large"
    }
}
