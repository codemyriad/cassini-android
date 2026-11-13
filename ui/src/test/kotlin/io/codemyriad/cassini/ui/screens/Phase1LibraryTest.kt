package io.codemyriad.cassini.ui.screens

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import io.codemyriad.cassini.ui.components.Highlighted
import io.codemyriad.cassini.ui.components.NoteRow
import io.codemyriad.cassini.ui.components.NoteRowAction
import io.codemyriad.cassini.ui.components.NoteStatusUi
import io.codemyriad.cassini.ui.fixtures.BoardFixtures
import io.codemyriad.cassini.ui.format.DayLabel
import io.codemyriad.cassini.ui.format.ProvideFixedFormat
import io.codemyriad.cassini.ui.library.DaySection
import io.codemyriad.cassini.ui.library.LibraryEvent
import io.codemyriad.cassini.ui.library.LibraryScreen
import io.codemyriad.cassini.ui.theme.CassiniTheme
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS-w390dp-h844dp-xhdpi")
class Phase1LibraryTest {
    @get:Rule val rule = createComposeRule()

    private fun themed(locale: Locale = Locale.ENGLISH, content: @Composable () -> Unit) {
        rule.setContent {
            CassiniTheme {
                ProvideFixedFormat(locale = locale, content = content)
            }
        }
    }

    @Test fun searchAndTheHiddenHeadingStayPinnedWhileRowsScroll() {
        val rows = (0 until 40).map {
            BoardFixtures.supplier.copy(id = "meeting-$it", title = "Meeting $it")
        }.toImmutableList()
        val state = BoardFixtures.library.copy(sections = persistentListOf(DaySection(DayLabel.Today, rows)))
        themed { LibraryScreen(state, onEvent = {}) }
        rule.onNodeWithContentDescription("All meetings").assert(isHeading())
        rule.onAllNodesWithText("All meetings").assertCountEquals(0)
        rule.onAllNodesWithText("Notes").assertCountEquals(0)
        val bar = rule.onNodeWithTag("library-search-bar").getBoundsInRoot()
        val last = state.sections.last().rows.last().title
        rule.onNodeWithTag("library-list").performScrollToNode(hasContentDescription(last, substring = true))
        assertEquals(bar, rule.onNodeWithTag("library-search-bar").getBoundsInRoot())
        rule.onNodeWithContentDescription("Trash").assertIsDisplayed()
        rule.onNodeWithTag("saturn-nav", useUnmergedTree = true).assertIsDisplayed()
        rule.onAllNodesWithText("Transcription runs on this phone.").assertCountEquals(0)
        rule.onAllNodesWithText("Transcribing Weekly product sync").assertCountEquals(0)
    }

    @Test fun emptySearchBackAndClearPreserveTheBarBoundsAndFocus() {
        var state by mutableStateOf(BoardFixtures.library)
        lateinit var back: OnBackPressedDispatcher
        val events = mutableListOf<LibraryEvent>()
        themed {
            back = requireNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            LibraryScreen(state, onEvent = {
                events += it
                when (it) {
                    is LibraryEvent.Query -> state = state.copy(query = it.text)
                    LibraryEvent.ClearQuery -> state = state.copy(query = "", results = null)
                    else -> Unit
                }
            })
        }
        val bar = rule.onNodeWithTag("library-search-bar").getBoundsInRoot()
        val field = rule.onNode(hasSetTextAction())
        field.performClick()
        rule.onNodeWithContentDescription("Close search").assertIsDisplayed()
        assertEquals(bar, rule.onNodeWithTag("library-search-bar").getBoundsInRoot())
        field.performTextInput("budget")
        rule.onNodeWithContentDescription("Clear search").performClick()
        field.assert(hasText("")).assertIsFocused()
        rule.onNodeWithContentDescription("Close search").assertIsDisplayed()
        rule.runOnIdle { back.onBackPressed() }
        rule.onNodeWithTag("saturn-nav", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(bar, rule.onNodeWithTag("library-search-bar").getBoundsInRoot())
        rule.runOnIdle { assertEquals(2, events.count { it == LibraryEvent.ClearQuery }) }
    }

    @Test
    @Config(qualifiers = "fr-rFR-w390dp-h844dp-xhdpi", fontScale = 1.5f)
    fun frenchSearchHintFitsWithoutShorteningItsAccessibleDescription() {
        themed(Locale.FRENCH) { LibraryScreen(BoardFixtures.library, onEvent = {}) }
        val field = rule.onNodeWithContentDescription("Rechercher dans toutes les réunions")
        field.assertIsDisplayed()
        val hint = rule.onNodeWithText("Rechercher", useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        hint.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        assertFalse(layouts.single().isLineEllipsized(0))
        val bar = rule.onNodeWithTag("library-search-bar").getBoundsInRoot()
        field.performClick().performTextInput("budget")
        assertEquals(bar, rule.onNodeWithTag("library-search-bar").getBoundsInRoot())
    }

    @Test fun finishedRowsUseCountPillsAndKeepOnlySearchSnippets() {
        var row by mutableStateOf(
            BoardFixtures.supplier.copy(
                day = null,
                status = NoteStatusUi.Transcribed(1, 20),
                preview = Highlighted("Why the search matched"),
            ),
        )
        themed { NoteRow(row, DayLabel.Today, onAction = {}) }
        rule.onNodeWithText("1 speaker", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Transcribed", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("Why the search matched", useUnmergedTree = true).assertDoesNotExist()
        rule.onNode(hasContentDescription("transcribed, 1 speaker", substring = true)).assertExists()
        rule.runOnIdle { row = row.copy(day = DayLabel.Today) }
        rule.onNodeWithText("Why the search matched", useUnmergedTree = true).assertIsDisplayed()
        rule.onNode(hasContentDescription("Why the search matched", substring = true)).assertExists()
        rule.runOnIdle { row = row.copy(status = NoteStatusUi.Transcribed(1, 0), preview = null) }
        rule.onNodeWithText("No speech", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("1 speaker", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun visibleEtaIsAvailableToTalkBackWithoutReplacingTheEstablishedStatusDescription() {
        var row by mutableStateOf(BoardFixtures.weeklySync.copy(transcriptionMinutes = 4))
        themed { NoteRow(row, DayLabel.Today, onAction = {}) }
        val node = rule.onNode(hasContentDescription("transcribing", substring = true))
        node.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Transcribing · 4 min"))
        val description = node.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
        assertTrue(description, description.endsWith(", transcribing"))
        rule.runOnIdle { row = row.copy(transcriptionMinutes = 0) }
        node.assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                "Transcribing, less than a minute left",
            ),
        )
    }

    @Test fun recoveryActionsRemainVisibleWithoutMiddleDots() {
        var row by mutableStateOf(BoardFixtures.supplier.copy(status = NoteStatusUi.Paused(60_000)))
        val actions = mutableListOf<NoteRowAction>()
        themed { NoteRow(row, DayLabel.Today, onAction = { actions += it }) }
        rule.onNodeWithText("Resume", useUnmergedTree = true).performClick()
        rule.onNodeWithText("· Resume", useUnmergedTree = true).assertDoesNotExist()
        rule.runOnIdle { row = row.copy(status = NoteStatusUi.Failed) }
        rule.onNodeWithText("Try again", useUnmergedTree = true).performClick()
        rule.runOnIdle { assertEquals(listOf(NoteRowAction.RESUME, NoteRowAction.TRY_AGAIN), actions) }
    }
}
