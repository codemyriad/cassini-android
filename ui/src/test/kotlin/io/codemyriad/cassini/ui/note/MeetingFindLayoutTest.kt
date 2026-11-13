package io.codemyriad.cassini.ui.note

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.codemyriad.cassini.ui.fixtures.BoardFixtures
import io.codemyriad.cassini.ui.format.ProvideFixedFormat
import io.codemyriad.cassini.ui.theme.CassiniTheme
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeetingFindLayoutTest {
    @get:Rule val rule = createComposeRule()

    @Test
    @Config(qualifiers = "en-rUS-w360dp-h844dp-xhdpi")
    fun englishLargeText() = layout(2f)

    @Test
    @Config(qualifiers = "it-rIT-w360dp-h844dp-xhdpi")
    fun italianLargeText() = layout(2f)

    @Test
    @Config(qualifiers = "fr-rFR-w360dp-h844dp-xhdpi")
    fun frenchLargeText() = layout(2f)

    @Test
    @Config(qualifiers = "es-rES-w360dp-h844dp-xhdpi")
    fun spanishLargeText() = layout(2f)

    @Test
    @Config(qualifiers = "pt-rBR-w360dp-h844dp-xhdpi")
    fun brazilianPortugueseLargeText() = layout(2f)

    @Test
    @Config(qualifiers = "pt-rPT-w360dp-h844dp-xhdpi")
    fun europeanPortugueseLargeText() = layout(2f)

    @Test
    @Config(qualifiers = "en-rUS-w390dp-h844dp-xhdpi")
    fun ordinaryTextKeepsUsableInputAndTheSameActions() = layout(1f)

    private fun layout(scale: Float) {
        val locale = java.util.Locale.getDefault()
        val base = BoardFixtures.readAndListen.copy(activeWord = null, playback = null)
        var search by mutableStateOf(TranscriptSearchUi("needle", persistentListOf(), 0))
        rule.setContent {
            CassiniTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                    ProvideFixedFormat(locale = locale) {
                        NoteScreen(base.copy(search = search), onEvent = { event ->
                            if (event is NoteEvent.SearchQuery) search = search.copy(query = event.query)
                        })
                    }
                }
            }
        }
        checkBounds()
        rule.onNodeWithTag("meeting-find-query").performClick().performTextReplacement("typed query")
        rule.onNodeWithTag("meeting-find-query").assert(hasText("typed query"))
        rule.runOnIdle {
            search = search.copy(
                matches = List(34) { TranscriptMatch(base.turns.first().id, 0..3) }.toImmutableList(),
                current = 11,
            )
        }
        checkBounds()
    }

    private fun checkBounds() {
        val query = rule.onNodeWithTag("meeting-find-query").assertIsDisplayed().getBoundsInRoot()
        val close = rule.onNodeWithTag("meeting-find-close").assertIsDisplayed().getBoundsInRoot()
        val previous = rule.onNodeWithTag("meeting-find-previous").assertIsDisplayed().getBoundsInRoot()
        val next = rule.onNodeWithTag("meeting-find-next").assertIsDisplayed().getBoundsInRoot()
        val count = rule.onNodeWithTag("meeting-find-count").assertIsDisplayed().getBoundsInRoot()
        val window = rule.onRoot().getBoundsInRoot()
        assertTrue("The editable query retains usable width", query.right - query.left >= 120.dp)
        assertTrue(close.right <= query.left)
        for (bounds in listOf(previous, next, count)) {
            assertTrue(bounds.left >= window.left && bounds.right <= window.right)
            assertTrue(bounds.left >= query.right || bounds.top >= query.bottom)
            assertTrue(bounds.left >= close.right || bounds.top >= close.bottom)
        }
        assertTrue(count.right <= previous.left)
        assertTrue(previous.right <= next.left)
        for (bounds in listOf(close, previous, next)) {
            assertTrue(bounds.right - bounds.left >= 48.dp)
            assertTrue(bounds.bottom - bounds.top >= 48.dp)
        }
    }
}
