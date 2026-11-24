package io.codemyriad.cassini.ui.screens

import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import io.codemyriad.cassini.ui.BoardTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

/**
 * Shared Roborazzi screenshot suite for tablet and foldable form factors across the four key screens
 * (Library, Recording, Read & Listen, Settings) in light and dark themes.
 */
abstract class FormFactorBoardTest(
    private val lightDir: String,
    private val darkDir: String,
    private val expectedWidthDp: Int,
    private val expectedHeightDp: Int,
    private val expectedMinSmallestWidthDp: Int,
) : BoardTest() {
    private fun captureScreen(slug: String, dark: Boolean) {
        var widthDp = 0
        var heightDp = 0
        var smallestWidthDp = 0
        board(if (dark) darkDir else lightDir, slug, dark = dark) {
            val density = LocalDensity.current
            val container = LocalWindowInfo.current.containerSize
            widthDp = with(density) { container.width.toDp().value.roundToInt() }
            heightDp = with(density) { container.height.toDp().value.roundToInt() }
            smallestWidthDp = LocalConfiguration.current.smallestScreenWidthDp
            BoardContent(slug)
        }
        assertEquals("screenWidthDp for $slug", expectedWidthDp, widthDp)
        assertEquals("screenHeightDp for $slug", expectedHeightDp, heightDp)
        assertTrue(
            "smallestScreenWidthDp ($smallestWidthDp) must be >= $expectedMinSmallestWidthDp for $slug",
            smallestWidthDp >= expectedMinSmallestWidthDp,
        )
        val unexpectedCuts = cutTexts().filterNot { expectedWidthDp < NARROW_WIDTH_DP && it.endsWith(" (ellipsized)") }
        assertEquals("Texts that do not fit on $slug", emptyList<String>(), unexpectedCuts)
    }

    @Test fun libraryLight() = captureScreen("00-library", dark = false)

    @Test
    @Config(qualifiers = "+night")
    fun libraryDark() = captureScreen("00-library", dark = true)

    @Test fun recordingLight() = captureScreen("01-recording", dark = false)

    @Test
    @Config(qualifiers = "+night")
    fun recordingDark() = captureScreen("01-recording", dark = true)

    @Test fun readAndListenLight() = captureScreen("03-read-and-listen", dark = false)

    @Test
    @Config(qualifiers = "+night")
    fun readAndListenDark() = captureScreen("03-read-and-listen", dark = true)

    @Test fun settingsLight() = captureScreen("08-settings", dark = false)

    @Test
    @Config(qualifiers = "+night")
    fun settingsDark() = captureScreen("08-settings", dark = true)

    companion object {
        /** 10-inch tablet in portrait (sw600dp+): 800 x 1280 dp at xhdpi (2x). */
        const val TABLET_PORTRAIT = "w800dp-h1280dp-port-xhdpi"

        /** 10-inch tablet in landscape (sw600dp+): 1280 x 800 dp at xhdpi (2x). */
        const val TABLET_LANDSCAPE = "w1280dp-h800dp-land-xhdpi"

        /** Foldable narrow cover display: 320 x 844 dp at xhdpi (2x). */
        const val FOLDABLE_FOLDED = "w320dp-h844dp-port-xhdpi"

        /** Foldable unfolded inner display (sw600dp+): 673 x 841 dp at xhdpi (2x). */
        const val FOLDABLE_UNFOLDED = "w673dp-h841dp-port-xhdpi"

        private const val NARROW_WIDTH_DP = 360
    }
}

@Config(qualifiers = FormFactorBoardTest.TABLET_PORTRAIT)
class TabletPortraitScreenshotTest : FormFactorBoardTest(
    lightDir = "tablet-portrait",
    darkDir = "tablet-portrait-dark",
    expectedWidthDp = 800,
    expectedHeightDp = 1280,
    expectedMinSmallestWidthDp = 600,
)

@Config(qualifiers = FormFactorBoardTest.TABLET_LANDSCAPE)
class TabletLandscapeScreenshotTest : FormFactorBoardTest(
    lightDir = "tablet-landscape",
    darkDir = "tablet-landscape-dark",
    expectedWidthDp = 1280,
    expectedHeightDp = 800,
    expectedMinSmallestWidthDp = 600,
)

@Config(qualifiers = FormFactorBoardTest.FOLDABLE_FOLDED)
class FoldableFoldedScreenshotTest : FormFactorBoardTest(
    lightDir = "foldable-folded",
    darkDir = "foldable-folded-dark",
    expectedWidthDp = 320,
    expectedHeightDp = 844,
    expectedMinSmallestWidthDp = 320,
)

@Config(qualifiers = FormFactorBoardTest.FOLDABLE_UNFOLDED)
class FoldableUnfoldedScreenshotTest : FormFactorBoardTest(
    lightDir = "foldable-unfolded",
    darkDir = "foldable-unfolded-dark",
    expectedWidthDp = 673,
    expectedHeightDp = 841,
    expectedMinSmallestWidthDp = 600,
)
