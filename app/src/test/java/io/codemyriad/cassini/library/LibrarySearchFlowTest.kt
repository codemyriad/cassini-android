package io.codemyriad.cassini.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.codemyriad.cassini.core.library.SearchFilters
import io.codemyriad.cassini.data.db.SearchRow
import io.codemyriad.cassini.ui.library.LibraryEvent
import io.codemyriad.cassini.ui.library.LibraryUiState
import io.codemyriad.cassini.ui.library.SearchDateUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.ContinuationInterceptor

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LibrarySearchFlowTest {
    private val main = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(main)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun theSharedProductionInventoryPipelineCollectsUpstreamOnCompute() = runTest(main) {
        val compute = StandardTestDispatcher(testScheduler, name = "inventory-compute")
        val base = FakeLibraryPorts()
        val observed = mutableListOf<ContinuationInterceptor?>()
        val ports = object : LibraryPorts by base {
            override fun namedSpeakers(): Flow<List<String>> = flow {
                observed += currentCoroutineContext()[ContinuationInterceptor]
                emit(listOf("Ada", "Ben"))
            }
        }
        val viewModel = LibraryViewModel(SavedStateHandle(), ports, CLOCK, compute)
        try {
            backgroundScope.launch { viewModel.state.collect {} }
            runCurrent()
            assertEquals(listOf("Ada", "Ben"), viewModel.state.value.searchFilters.speakers)
            assertEquals(1, observed.size)
            // Covers the upstream collection before shareIn; state.flowOn cannot satisfy this assertion.
            assertSame(compute, observed.single())
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test fun inventoryAndUnrelatedUpdatesKeepTheSearchCollectorAndItsExcerptCache() = runTest(main) {
        val compute = StandardTestDispatcher(testScheduler, name = "search-compute")
        val base = FakeLibraryPorts()
        val inventories = MutableSharedFlow<List<String>>(replay = 1)
        val zones = MutableSharedFlow<ZoneId>(replay = 1)
        inventories.tryEmit(listOf("Ada"))
        zones.tryEmit(ROME)
        val hit = SearchRow(libraryRow("hit"), "\u0002delivery\u0003", "Meeting")
        base.searchRows.value = mapOf("delivery" to listOf(hit))
        val collectors = mutableListOf<Any>()
        var cancellations = 0
        val ports = object : LibraryPorts by base {
            override fun namedSpeakers(): Flow<List<String>> = inventories

            override val calendarZone: Flow<ZoneId> = zones

            override fun search(
                query: String,
                filters: SearchFilters,
                zone: ZoneId,
                today: LocalDate,
            ): Flow<List<SearchRow>> =
                flow {
                    // NoteRepository creates SearchMatches once per collector, inside this same flow boundary.
                    collectors += Any()
                    emitAll(base.search(query, filters, zone, today))
                }.onCompletion { cancellations++ }
        }
        val viewModel = LibraryViewModel(SavedStateHandle(), ports, CLOCK, compute)
        val states = mutableListOf<LibraryUiState>()
        try {
            backgroundScope.launch { viewModel.state.collect { states += it } }
            runCurrent()
            viewModel.onEvent(LibraryEvent.Query("delivery"))
            advanceUntilIdle()
            val owner = collectors.single()
            val results = viewModel.state.value.results
            states.clear()
            // SharedFlow deliberately emits equal lists, as a Room query invalidated by notes would.
            inventories.emit(listOf("Ada"))
            runCurrent()
            inventories.emit(listOf("Ada", "Ben"))
            runCurrent()
            assertEquals(listOf("Ada", "Ben"), viewModel.state.value.searchFilters.speakers)
            assertSame(results, viewModel.state.value.results)
            base.rows.value = listOf(libraryRow("unrelated"))
            inventories.emit(listOf("Ada", "Ben"))
            zones.emit(ROME)
            runCurrent()
            assertEquals(1, collectors.size)
            assertSame(owner, collectors.single())
            assertEquals(0, cancellations)
            assertTrue(states.isNotEmpty())
            assertTrue("Inventory updates must not clear results", states.all { it.results?.size == 1 })
            viewModel.onEvent(LibraryEvent.FilterDate(SearchDateUi.TODAY))
            runCurrent()
            assertEquals(2, collectors.size)
            assertEquals(1, cancellations)
            viewModel.onEvent(LibraryEvent.FilterDate(SearchDateUi.TODAY))
            viewModel.onEvent(LibraryEvent.Query(" delivery "))
            zones.emit(ROME)
            runCurrent()
            advanceUntilIdle()
            assertEquals("Equal domain inputs must not restart", 2, collectors.size)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }
}
