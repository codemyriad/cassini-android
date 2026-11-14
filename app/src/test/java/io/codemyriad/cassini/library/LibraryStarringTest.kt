package io.codemyriad.cassini.library

import androidx.lifecycle.SavedStateHandle
import io.codemyriad.cassini.core.library.SearchDate
import io.codemyriad.cassini.core.library.SearchFilters
import io.codemyriad.cassini.ui.library.LibraryEvent
import io.codemyriad.cassini.ui.library.LibraryFilterUi
import io.codemyriad.cassini.ui.library.LibrarySheet
import io.codemyriad.cassini.ui.library.LibraryUiState
import io.codemyriad.cassini.ui.library.SearchDateUi
import io.codemyriad.cassini.ui.note.NoteAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Starring, the Library's All / Starred row and the Starred / This year search filters (L-34/L-35, D99). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LibraryStarringTest {
    private val dispatcher = StandardTestDispatcher()
    private val ports = FakeLibraryPorts()
    private val saved = SavedStateHandle()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): LibraryViewModel {
        val viewModel = LibraryViewModel(saved, ports, CLOCK, dispatcher)
        backgroundScope.launch { viewModel.state.collect {} }
        runCurrent()
        return viewModel
    }

    private fun LibraryViewModel.now(): LibraryUiState = state.value

    private fun LibraryUiState.restIds(): List<String> = sections.flatMap { section -> section.rows.map { it.id } }

    @Test
    fun theAllStarredRowNarrowsTheLibraryAndIsRememberedThroughThePorts() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        assertFalse("no row before the first note", viewModel.now().libraryFilters)
        ports.add(libraryRow("a", createdAt = daysAgo(0)))
        ports.add(libraryRow("b", createdAt = daysAgo(1)).copy(starred = true))
        advanceUntilIdle()
        var state = viewModel.now()
        assertTrue(state.libraryFilters)
        assertEquals(LibraryFilterUi.ALL, state.libraryFilter)
        assertEquals(listOf("a", "b"), state.restIds())
        assertEquals(listOf(false, true), state.sections.flatMap { it.rows }.map { it.starred })

        viewModel.onEvent(LibraryEvent.SelectLibraryFilter(LibraryFilterUi.STARRED))
        advanceUntilIdle()
        state = viewModel.now()
        assertTrue("libraryStarredOnly true" in ports.actions)
        assertEquals(LibraryFilterUi.STARRED, state.libraryFilter)
        assertEquals(listOf("b"), state.restIds())
        assertFalse(state.starredEmpty)

        viewModel.onEvent(LibraryEvent.SetStarred("b", starred = false))
        advanceUntilIdle()
        state = viewModel.now()
        assertTrue("starred b false" in ports.actions)
        assertTrue(state.restIds().isEmpty())
        assertTrue("Starred with nothing starred shows library.starred.empty", state.starredEmpty)
        assertNull("the empty-Library hero is for an empty Library only", state.hero)

        viewModel.onEvent(LibraryEvent.SelectLibraryFilter(LibraryFilterUi.ALL))
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), viewModel.now().restIds())
        assertFalse(viewModel.now().starredEmpty)
    }

    @Test
    fun aRememberedStarredChoiceAppliesWhenTheLibraryOpens() = runTest(dispatcher) {
        ports.libraryStarredOnly.value = true
        ports.add(libraryRow("a"))
        ports.add(libraryRow("b").copy(starred = true))
        val viewModel = viewModel()
        advanceUntilIdle()
        assertEquals(LibraryFilterUi.STARRED, viewModel.now().libraryFilter)
        assertEquals(listOf("b"), viewModel.now().restIds())
    }

    @Test
    fun searchCarriesOverStarredAndResetsTheOtherFiltersWhenItCloses() = runTest(dispatcher) {
        ports.libraryStarredOnly.value = true
        ports.add(libraryRow("a").copy(starred = true))
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.onEvent(LibraryEvent.Query("delivery"))
        advanceUntilIdle()
        assertTrue(viewModel.now().searchFilters.starred)
        assertFalse("the filter row at rest is not shown while searching", viewModel.now().libraryFilters)
        assertEquals(SearchFilters(starred = true), ports.filterRequests.last())

        viewModel.onEvent(LibraryEvent.ToggleSearchStarred)
        viewModel.onEvent(LibraryEvent.FilterDate(SearchDateUi.YEAR))
        viewModel.onEvent(LibraryEvent.ToggleRecordedByMe)
        advanceUntilIdle()
        assertEquals(SearchFilters(date = SearchDate.YEAR, recordedByMe = true), ports.filterRequests.last())
        assertTrue("the search chip leaves the Library's choice alone", ports.libraryStarredOnly.value)
        assertFalse(ports.actions.any { it.startsWith("libraryStarredOnly") })

        viewModel.onEvent(LibraryEvent.ClearQuery)
        advanceUntilIdle()
        assertEquals(LibraryFilterUi.STARRED, viewModel.now().libraryFilter)
        viewModel.onEvent(LibraryEvent.Query("delivery"))
        advanceUntilIdle()
        assertEquals(SearchFilters(starred = true), ports.filterRequests.last())

        // Typing more keeps the filters chosen during this search.
        viewModel.onEvent(LibraryEvent.ToggleSearchStarred)
        viewModel.onEvent(LibraryEvent.Query("delivery date"))
        advanceUntilIdle()
        assertFalse(viewModel.now().searchFilters.starred)
    }

    @Test
    fun anOpenSearchRestoredAfterProcessDeathTakesTheLibraryChoice() = runTest(dispatcher) {
        ports.libraryStarredOnly.value = true
        saved["query"] = "delivery"
        val viewModel = viewModel()
        advanceUntilIdle()
        assertTrue(viewModel.now().searchFilters.starred)
        assertEquals(SearchFilters(starred = true), ports.filterRequests.last())
    }

    @Test
    fun theActionsSheetStarsAndUnstars() = runTest(dispatcher) {
        ports.add(libraryRow("a"))
        val viewModel = viewModel()
        viewModel.onEvent(LibraryEvent.LongPress("a"))
        advanceUntilIdle()
        assertFalse((viewModel.now().sheet as LibrarySheet.Actions).ui.starred)
        viewModel.onEvent(LibraryEvent.SheetAction(NoteAction.STAR))
        advanceUntilIdle()
        assertTrue("starred a true" in ports.actions)
        assertNull(viewModel.now().sheet)
        assertTrue(viewModel.now().sections.single().rows.single().starred)

        viewModel.onEvent(LibraryEvent.LongPress("a"))
        advanceUntilIdle()
        assertTrue((viewModel.now().sheet as LibrarySheet.Actions).ui.starred)
        viewModel.onEvent(LibraryEvent.SheetAction(NoteAction.UNSTAR))
        advanceUntilIdle()
        assertTrue("starred a false" in ports.actions)
        assertFalse(viewModel.now().sections.single().rows.single().starred)
    }
}
