package io.codemyriad.cassini.ui.note

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import io.codemyriad.cassini.ui.banner.BannerKind
import io.codemyriad.cassini.ui.banner.BannerSpec
import io.codemyriad.cassini.ui.components.TranscriptFixtures
import io.codemyriad.cassini.ui.fixtures.BoardFixtures
import io.codemyriad.cassini.ui.format.ProvideFixedFormat
import io.codemyriad.cassini.ui.theme.CassiniColors
import io.codemyriad.cassini.ui.theme.CassiniTheme
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS-w390dp-h844dp-xhdpi")
class MeetingBannerLayoutTest {
    @get:Rule val rule = createComposeRule()
    private val turns = List(20) { index ->
        BoardFixtures.readGiulia.copy(
            id = "turn-$index",
            words = TranscriptFixtures.words(List(150) { "paragraph$index" }.joinToString(" "), index * 10_000L),
        )
    }.toImmutableList()
    private var state by mutableStateOf(
        BoardFixtures.readAndListen.copy(
            turns = turns,
            activeWord = null,
            playback = null,
            banners = persistentListOf(BannerSpec(BannerKind.TRUST_VERIFIED)),
        ),
    )
    private lateinit var colors: CassiniColors
    private var density = 0f

    @Test fun detailsFollowTheirControlsAndScrollWithTheMetadata() {
        show()
        val version = rule.onNode(hasText("Version 2 of 2", substring = true)).getBoundsInRoot()
        val banner = rule.onNodeWithTag("meeting-banner-TRUST_VERIFIED").assertIsDisplayed().getBoundsInRoot()
        val divider = rule.onNodeWithTag("meeting-header-divider").getBoundsInRoot()
        assertTrue(banner.top >= version.bottom)
        assertTrue(divider.top >= banner.bottom)
        rule.onNodeWithTag("transcript-list").performScrollToIndex(12)
        rule.onNodeWithTag("meeting-banner-TRUST_VERIFIED").assertIsNotDisplayed()
        rule.onNodeWithTag("meeting-scrolled-edge").assertIsDisplayed()
    }

    @Test fun queuedDocumentControlsAndBannerStayPinnedAboveOneBoundary() {
        state = state.copy(mode = NoteMode.Queued(0, overDocument = true))
        show()
        val version = rule.onNode(hasText("Version 2 of 2", substring = true)).getBoundsInRoot()
        val banner = rule.onNodeWithTag("meeting-banner-TRUST_VERIFIED").getBoundsInRoot()
        rule.onNodeWithTag("transcript-list").performScrollToIndex(12)
        assertEquals(version, rule.onNode(hasText("Version 2 of 2", substring = true)).getBoundsInRoot())
        assertEquals(banner, rule.onNodeWithTag("meeting-banner-TRUST_VERIFIED").getBoundsInRoot())
        assertSingleBoundary()
    }

    @Test fun findHasOneBoundaryImmediatelyBelowItsHeader() {
        state = state.copy(banners = persistentListOf(), search = TranscriptSearchUi("missing", persistentListOf(), 0))
        show()
        val bar = rule.onNodeWithTag("meeting-find-bar").getBoundsInRoot()
        val pane = rule.onNodeWithTag("transcript-list").getBoundsInRoot()
        assertEquals(bar.bottom, pane.top)
        assertSingleBoundary()
    }

    private fun assertSingleBoundary() {
        val pane = rule.onNodeWithTag("transcript-list").getBoundsInRoot()
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        val x = (2 * density).roundToInt()
        val top = (pane.top.value * density).roundToInt()
        for (y in top until top + density.roundToInt()) assertEquals(colors.line, pixels[x, y])
        assertEquals(colors.surface, pixels[x, top + density.roundToInt()])
    }

    private fun show() {
        rule.setContent {
            CassiniTheme {
                colors = CassiniTheme.colors
                density = LocalDensity.current.density
                ProvideFixedFormat(locale = Locale.ENGLISH) { NoteScreen(state, onEvent = {}) }
            }
        }
    }
}
