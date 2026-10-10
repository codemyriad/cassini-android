package org.cassini.ui.screens

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import org.cassini.ui.components.WordRef
import org.cassini.ui.components.turnText
import org.cassini.ui.fixtures.BoardFixtures
import org.cassini.ui.fixtures.VariantFixtures
import org.cassini.ui.format.FIXED_ZONE
import org.cassini.ui.format.ProvideFixedFormat
import org.cassini.ui.library.LibraryEvent
import org.cassini.ui.library.LibraryScreen
import org.cassini.ui.note.NoteEvent
import org.cassini.ui.note.NoteScreen
import org.cassini.ui.theme.CassiniTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/**
 * What the Library and Read & listen send (features.md sections 1 and 3.3): a long press on a row, a word tap, and
 * system back, which closes the in-note search before the screen's own Back. Native graphics give the transcript
 * its real geometry, which the word tap needs.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class ScreenBehaviourTest {
    @get:Rule val rule = createComposeRule()

    private lateinit var back: OnBackPressedDispatcher

    private fun setThemed(content: @Composable () -> Unit) {
        rule.setContent {
            back = requireNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            CassiniTheme(darkTheme = false) {
                ProvideFixedFormat(locale = Locale.ENGLISH, is24Hour = true, zone = FIXED_ZONE, content = content)
            }
        }
    }

    @Test fun aLongPressOnARowAsksForTheActionsSheet() {
        val events = mutableListOf<LibraryEvent>()
        setThemed { LibraryScreen(BoardFixtures.library, onEvent = { events += it }) }
        rule.onNode(hasContentDescription("Call with supplier,", substring = true) and hasClickAction())
            .performTouchInput { longClick() }
        rule.runOnIdle { assertEquals(listOf(LibraryEvent.LongPress(BoardFixtures.supplier.id)), events) }
    }

    @Test fun aWordTapAsksToPlayFromThatWord() {
        val events = mutableListOf<NoteEvent>()
        setThemed { NoteScreen(BoardFixtures.readAndListen, onEvent = { events += it }) }
        val paolo = BoardFixtures.readPaolo
        val paragraph = rule.onNode(hasText(turnText(paolo.words)))
        val index = 12
        val start = turnText(paolo.words.subList(0, index)).length + 1
        val centre = paragraph.textLayout().getBoundingBox(start + paolo.words[index].text.length / 2).center
        paragraph.performTouchInput { click(centre) }
        rule.runOnIdle { assertEquals(listOf(NoteEvent.WordTap(WordRef(paolo.id, index))), events) }
    }

    @Test fun backClosesTheSearchBeforeLeaving() {
        val events = mutableListOf<NoteEvent>()
        var state by mutableStateOf(VariantFixtures.read.getValue("read-search"))
        setThemed {
            NoteScreen(state, onEvent = {
                events += it
                if (it == NoteEvent.CloseSearch) state = state.copy(search = null)
            })
        }
        rule.runOnIdle { back.onBackPressed() }
        rule.runOnIdle { assertEquals(listOf<NoteEvent>(NoteEvent.CloseSearch), events) }
        // With the search closed the screen no longer takes system back: the navigation in :app pops the screen.
        rule.runOnIdle { assertEquals(false, back.hasEnabledCallbacks()) }
        rule.onNodeWithContentDescription("Back to library").performClick()
        rule.runOnIdle { assertEquals(listOf(NoteEvent.CloseSearch, NoteEvent.Back), events) }
    }

    @Test fun theSearchHeaderBackButtonClosesTheSearch() {
        val events = mutableListOf<NoteEvent>()
        setThemed { NoteScreen(VariantFixtures.read.getValue("read-search"), onEvent = { events += it }) }
        rule.onNodeWithContentDescription("Close search").performClick()
        rule.runOnIdle { assertEquals(listOf<NoteEvent>(NoteEvent.CloseSearch), events) }
    }

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }
}
