package org.cassini.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import org.cassini.ui.format.Durations
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
 * The player in a short window: one row only while the slider keeps 96 dp, else the two rows of board 03. Either way
 * every control is a 48 dp target, nothing overlaps and the times stay on one line. The bar widths are those of the
 * note screen, which caps its content at 560 dp. Native graphics give the text its real width.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w844dp-h390dp-xhdpi")
class PlaybackBarLandscapeTest {
    @get:Rule val rule = createComposeRule()

    private lateinit var density: Density
    private var timeWidth = 0
    private var timeHeight = 0

    private fun setBar(width: Dp, fontScale: Float, durationMs: Long) {
        rule.setContent {
            CassiniTheme(darkTheme = false) {
                ProvideFixedFormat(locale = Locale.ENGLISH, is24Hour = true, zone = FIXED_ZONE) {
                    val scaled = Density(LocalDensity.current.density, fontScale)
                    CompositionLocalProvider(LocalDensity provides scaled) {
                        density = scaled
                        val time = rememberTextMeasurer()
                            .measure(Durations.clock(durationMs), CassiniTheme.type.monoSmall, softWrap = false)
                        timeWidth = time.size.width
                        timeHeight = time.size.height
                        Box(Modifier.width(width).testTag(BAR)) {
                            PlaybackBar(
                                positionMs = durationMs - 1_000,
                                durationMs = durationMs,
                                playing = false,
                                onSeek = {},
                                onSkipBack = {},
                                onSkipForward = {},
                                onPlayPause = {},
                            )
                        }
                    }
                }
            }
        }
    }

    @Test fun aWideLandscapeWindowHasOneRow() {
        setBar(560.dp, fontScale = 1f, durationMs = HALF_HOUR)
        assertOneRow()
    }

    @Test fun anHourLongNoteAtFontScale2KeepsOneRowWhenTheSliderFits() {
        setBar(560.dp, fontScale = 2f, durationMs = HOUR_AND_A_HALF)
        assertOneRow()
    }

    @Test fun anHourLongNoteAtFontScale2OnASmallPhoneHasTwoRows() {
        setBar(480.dp, fontScale = 2f, durationMs = HOUR_AND_A_HALF)
        assertTwoRows()
    }

    @Test
    @Config(qualifiers = "w390dp-h400dp-xhdpi")
    fun anUprightSplitScreenHasTwoRows() {
        setBar(390.dp, fontScale = 1f, durationMs = HOUR_AND_A_HALF)
        assertTwoRows()
    }

    private fun assertOneRow() {
        assertTrue("one-row bar ${height(bar())} tall", height(bar()) <= 80.dp)
        val slider = rule.onNodeWithContentDescription("Position").getUnclippedBoundsInRoot()
        assertTrue("slider ${slider.width} wide", slider.width >= 96.dp)
        val times = listOf(COMPACT_POSITION_TAG, COMPACT_LENGTH_TAG).map { tag ->
            val node = rule.onNode(hasTestTag(tag), useUnmergedTree = true)
            val bounds = node.getUnclippedBoundsInRoot()
            val given = node.fetchSemanticsNode().size
            // Its slot is as wide as the single line needs, and one line tall: not cut, not wrapped.
            assertTrue("$tag needs $timeWidth px and has ${given.width} px", given.width >= timeWidth)
            assertEquals("$tag height (px)", timeHeight, given.height)
            bounds
        }
        assertControls(extra = times)
    }

    private fun assertTwoRows() {
        assertTrue("two-row bar ${height(bar())} tall", height(bar()) > 80.dp)
        val compactTimes = rule.onAllNodes(hasTestTag(COMPACT_POSITION_TAG), useUnmergedTree = true)
        assertTrue("one-row times in two rows", compactTimes.fetchSemanticsNodes().isEmpty())
        // The times row spans the bar inside its 16 dp padding and holds both times on one line.
        val inner = with(density) { (bar().width - 32.dp).roundToPx() }
        assertTrue("two times of $timeWidth px in $inner px", 2 * timeWidth <= inner)
        assertControls(extra = emptyList())
    }

    /** The four controls are 48 dp targets inside the bar, and no two controls or times overlap. */
    private fun assertControls(extra: List<DpRect>) {
        val bar = bar()
        val controls = listOf("Back 10 seconds", "Play", "Forward 10 seconds", "Position").map { description ->
            val node = rule.onNodeWithContentDescription(description).assertTouchAtLeast48(density)
            node.getUnclippedBoundsInRoot().also { assertTrue("$description outside the bar", it.inside(bar)) }
        }
        val all = controls + extra
        for (i in all.indices) for (j in i + 1 until all.size) {
            assertTrue("${all[i]} overlaps ${all[j]}", !all[i].overlaps(all[j]))
        }
    }

    private fun bar(): DpRect = rule.onNode(hasTestTag(BAR)).getUnclippedBoundsInRoot()

    private fun height(r: DpRect): Dp = r.bottom - r.top

    private val DpRect.width: Dp get() = right - left

    private fun DpRect.inside(outer: DpRect) =
        left >= outer.left - TOLERANCE && right <= outer.right + TOLERANCE &&
            top >= outer.top - TOLERANCE && bottom <= outer.bottom + TOLERANCE

    private fun DpRect.overlaps(other: DpRect) =
        left < other.right - TOLERANCE && other.left < right - TOLERANCE &&
            top < other.bottom - TOLERANCE && other.top < bottom - TOLERANCE

    private companion object {
        const val BAR = "bar"
        const val HALF_HOUR = 1_430_000L // 23:50
        const val HOUR_AND_A_HALF = 5_030_000L // 1:23:50
        val TOLERANCE = 0.25.dp
    }
}
