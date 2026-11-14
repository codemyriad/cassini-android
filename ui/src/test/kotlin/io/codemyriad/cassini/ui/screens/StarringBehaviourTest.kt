package io.codemyriad.cassini.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import io.codemyriad.cassini.ui.components.NoteRow
import io.codemyriad.cassini.ui.components.NoteRowAction
import io.codemyriad.cassini.ui.fixtures.BoardFixtures
import io.codemyriad.cassini.ui.format.DayLabel
import io.codemyriad.cassini.ui.format.FIXED_ZONE
import io.codemyriad.cassini.ui.format.ProvideFixedFormat
import io.codemyriad.cassini.ui.library.LibraryEvent
import io.codemyriad.cassini.ui.library.LibraryFilterUi
import io.codemyriad.cassini.ui.library.LibraryScreen
import io.codemyriad.cassini.ui.library.LibrarySheet
import io.codemyriad.cassini.ui.library.LibraryUiState
import io.codemyriad.cassini.ui.library.SearchDateUi
import io.codemyriad.cassini.ui.library.SearchFiltersUi
import io.codemyriad.cassini.ui.note.NoteAction
import io.codemyriad.cassini.ui.theme.CassiniTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/** Starring, the Library's All / Starred row and the L-35 search row order (L-34/L-35, D99). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class StarringBehaviourTest {
    @get:Rule val rule = createComposeRule()

    private val events = mutableListOf<LibraryEvent>()

    private fun setThemed(content: @Composable () -> Unit) {
        rule.setContent {
            CassiniTheme(darkTheme = false) {
                ProvideFixedFormat(locale = Locale.ENGLISH, is24Hour = true, zone = FIXED_ZONE, content = content)
            }
        }
    }

    private fun show(state: LibraryUiState) = setThemed { LibraryScreen(state, onEvent = { events += it }) }

    private fun assertEvents(vararg expected: LibraryEvent) = rule.runOnIdle { assertEquals(expected.toList(), events) }

    @Test fun theLibraryRowAtRestIsASingleChoiceThatSendsTheOtherChip() {
        show(BoardFixtures.library.copy(libraryFilters = true))
        rule.onNodeWithContentDescription("Filters").assertExists()
        rule.onNodeWithText("All").assertIsSelected().performClick()
        rule.onNodeWithText("Starred").assertIsNotSelected().performClick()
        assertEvents(
            LibraryEvent.SelectLibraryFilter(LibraryFilterUi.ALL),
            LibraryEvent.SelectLibraryFilter(LibraryFilterUi.STARRED),
        )
        rule.onAllNodesWithText("No stars yet.").assertCountEquals(0)
    }

    @Test fun starredWithNothingStarredSaysSo() {
        show(
            BoardFixtures.library.copy(
                sections = persistentListOf(),
                libraryFilters = true,
                libraryFilter = LibraryFilterUi.STARRED,
                starredEmpty = true,
            ),
        )
        rule.onNodeWithText("Starred").assertIsSelected()
        rule.onNodeWithText("No stars yet.").assertExists()
    }

    @Test fun theSearchRowIsStarredSpeakerDateRecordedByMeAndOffersThisYear() {
        val filters = SearchFiltersUi(starred = true, speakers = persistentListOf("Ada"))
        show(BoardFixtures.library.copy(query = "delivery", libraryFilters = false, searchFilters = filters))
        rule.onAllNodesWithText("All").assertCountEquals(0)
        val lefts = listOf("Starred", "Speaker", "Any date", "Recorded by me").map {
            rule.onNodeWithText(it).getBoundsInRoot().left
        }
        assertEquals(lefts.sorted(), lefts)
        rule.onNodeWithText("Starred").assertIsSelected().performClick()
        rule.onNodeWithText("Any date").performClick()
        rule.onNodeWithText("This year").performClick()
        assertEvents(LibraryEvent.ToggleSearchStarred, LibraryEvent.FilterDate(SearchDateUi.YEAR))
    }

    @Test fun theChosenYearShowsOnItsChip() {
        show(BoardFixtures.library.copy(query = "delivery", searchFilters = SearchFiltersUi(date = SearchDateUi.YEAR)))
        rule.onNodeWithText("This year").assertIsSelected()
    }

    @Test fun aStarredRowSaysSoAndOffersUnstar() {
        val actions = mutableListOf<NoteRowAction>()
        setThemed {
            Column(Modifier.width(390.dp)) {
                NoteRow(BoardFixtures.supplier.copy(starred = true), DayLabel.Yesterday, onAction = { actions += it })
                NoteRow(BoardFixtures.boardReview, DayLabel.Yesterday, onAction = { actions += it })
            }
        }
        val starred = rule.onNode(hasContentDescription("Call with supplier,", substring = true))
        assertTrue(starred.description().contains(", starred"))
        assertEquals("Unstar", starred.customLabels()[1])
        val plain = rule.onNode(hasContentDescription("Board review prep,", substring = true))
        assertTrue(!plain.description().contains("starred"))
        val star = plain.fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == "Star" }
        val unstar = starred.fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == "Unstar" }
        rule.runOnIdle {
            star.action()
            unstar.action()
        }
        assertEquals(listOf(NoteRowAction.STAR, NoteRowAction.UNSTAR), actions)
    }

    @Test fun theActionsSheetStarsOrUnstars() {
        show(BoardFixtures.library.copy(sheet = LibrarySheet.Actions(BoardFixtures.noteActions)))
        rule.onNodeWithText("Star").performScrollTo().performClick()
        assertEvents(LibraryEvent.SheetAction(NoteAction.STAR))
    }

    @Test fun theActionsSheetOfAStarredNoteUnstars() {
        show(BoardFixtures.library.copy(sheet = LibrarySheet.Actions(BoardFixtures.noteActions.copy(starred = true))))
        rule.onAllNodesWithText("Star").assertCountEquals(0)
        rule.onNodeWithText("Unstar").performScrollTo().performClick()
        assertEvents(LibraryEvent.SheetAction(NoteAction.UNSTAR))
    }

    private fun SemanticsNodeInteraction.description(): String =
        fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].joinToString()

    private fun SemanticsNodeInteraction.customLabels(): List<String> =
        fetchSemanticsNode().config[SemanticsActions.CustomActions].map { it.label }
}
