package org.cassini.android.trash

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.cassini.data.db.TrashRow
import org.cassini.ui.format.DayLabel
import org.cassini.ui.trash.TrashBackTo
import org.cassini.ui.trash.TrashDialog
import org.cassini.ui.trash.TrashEvent
import org.cassini.ui.trash.TrashItemUi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** The Trash ViewModel over fake ports (features.md section 8). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TrashViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val ports = FakeTrashPorts()
    private var closed = 0

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(
        saved: SavedStateHandle = SavedStateHandle(),
        backTo: TrashBackTo = TrashBackTo.LIBRARY,
    ): TrashViewModel {
        val viewModel = TrashViewModel(saved, ports, backTo, CLOCK)
        backgroundScope.launch { viewModel.state.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.close.collect { closed++ } }
        advanceUntilIdle()
        return viewModel
    }

    @Test
    fun purgesOnOpenThenShowsTheCardsOldestDeletionFirstWithTheDaysLeft() = runTest(dispatcher) {
        val state = viewModel(backTo = TrashBackTo.SETTINGS).state.value!!
        assertEquals(listOf(NOW), ports.purgedAt)
        assertEquals(
            listOf(
                TrashItemUi("a", "Test recording", 60_000, DayLabel.Date(LocalDate.of(2026, 10, 2), false), 23),
                TrashItemUi("b", "Standup", 0, DayLabel.Yesterday, 29),
                TrashItemUi("c", "Today's", 5, DayLabel.Today, 30),
            ),
            state.items,
        )
        assertEquals(TrashBackTo.SETTINGS, state.backTo)
        assertNull(state.snackbar)
    }

    @Test
    fun restoreGivesTheNoteBack() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onEvent(TrashEvent.Restore("b"))
        advanceUntilIdle()
        assertEquals(listOf("b"), ports.restored)
        assertEquals(listOf("a", "c"), viewModel.state.value!!.items.map { it.id })
    }

    @Test
    fun deleteNowAsksFirstAndTheDialogSurvivesProcessDeath() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val viewModel = viewModel(saved)
        viewModel.onEvent(TrashEvent.DeleteNow("a"))
        advanceUntilIdle()
        assertEquals(TrashDialog.Delete("a", "Test recording"), viewModel.state.value!!.dialog)
        assertTrue(ports.deleted.isEmpty())

        val restored = viewModel(SavedStateHandle(mapOf("dialog" to saved.get<String>("dialog"))))
        assertEquals(TrashDialog.Delete("a", "Test recording"), restored.state.value!!.dialog)
        restored.onEvent(TrashEvent.ConfirmDialog)
        advanceUntilIdle()
        assertEquals(listOf("a"), ports.deleted)
        assertNull(restored.state.value!!.dialog)
    }

    @Test
    fun emptyAsksWithTheCountAndCancelKeepsEverything() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onEvent(TrashEvent.Empty)
        advanceUntilIdle()
        assertEquals(TrashDialog.EmptyAll(3), viewModel.state.value!!.dialog)
        viewModel.onEvent(TrashEvent.DismissDialog)
        advanceUntilIdle()
        assertEquals(0, ports.emptied)
        viewModel.onEvent(TrashEvent.Empty)
        viewModel.onEvent(TrashEvent.ConfirmDialog)
        advanceUntilIdle()
        assertEquals(1, ports.emptied)
        assertTrue(viewModel.state.value!!.items.isEmpty())
        // Empty is hidden on an empty Trash; the event alone opens nothing.
        viewModel.onEvent(TrashEvent.Empty)
        advanceUntilIdle()
        assertNull(viewModel.state.value!!.dialog)
    }

    @Test
    fun backCloses() = runTest(dispatcher) {
        viewModel().onEvent(TrashEvent.Back)
        advanceUntilIdle()
        assertEquals(1, closed)
    }

    private class FakeTrashPorts : TrashPorts {
        val rows = MutableStateFlow(
            listOf(
                TrashRow("a", "Test recording", 60_000, at(2026, 10, 2, 18)),
                TrashRow("b", "Standup", 0, at(2026, 10, 8, 23)),
                TrashRow("c", "Today's", 5, at(2026, 10, 9, 1)),
            ),
        )
        val purgedAt = mutableListOf<Instant>()
        val restored = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        var emptied = 0

        override fun trash(): Flow<List<TrashRow>> = rows

        override suspend fun purgeExpired(now: Instant, zone: ZoneId): Int {
            purgedAt += now
            return 0
        }

        override suspend fun restore(id: String): Boolean {
            restored += id
            rows.update { list -> list.filter { it.id != id } }
            return true
        }

        override suspend fun deleteNow(id: String): Boolean {
            deleted += id
            rows.update { list -> list.filter { it.id != id } }
            return true
        }

        override suspend fun emptyTrash(): Int {
            emptied++
            val count = rows.value.size
            rows.value = emptyList()
            return count
        }
    }

    private companion object {
        val ROME: ZoneId = ZoneId.of("Europe/Rome")

        /** Friday 9 Oct 2026, 12:00 in Rome: a note trashed on 2 Oct has 23 days left. */
        val NOW: Instant = LocalDateTime.of(2026, 10, 9, 12, 0).atZone(ROME).toInstant()
        val CLOCK: Clock = Clock.fixed(NOW, ROME)

        fun at(year: Int, month: Int, day: Int, hour: Int): Long =
            LocalDateTime.of(year, month, day, hour, 0).atZone(ROME).toInstant().toEpochMilli()
    }
}
