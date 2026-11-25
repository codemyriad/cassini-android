package io.codemyriad.cassini.library

import androidx.lifecycle.SavedStateHandle
import io.codemyriad.cassini.core.library.Origin
import io.codemyriad.cassini.ui.library.LibraryDialog
import io.codemyriad.cassini.ui.library.LibraryEvent
import io.codemyriad.cassini.ui.library.LibraryUiState
import io.codemyriad.cassini.ui.note.NoteAction
import io.codemyriad.cassini.ui.note.SetDateTimeUi
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** "Set date and time" from the Library's actions sheet (D112, L-87, D-1164). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LibrarySetDateTimeTest {
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

    /** An imported clip with no recording time, its file last saved at 11:20 and 61 s long. */
    private fun addImportedClip(id: String) {
        val row = libraryRow(id).copy(recordedAt = null, origin = Origin.IMPORTED_AUDIO)
        ports.add(row)
        val saved = LocalDateTime.of(2026, 10, 3, 11, 20).atZone(ROME).toInstant().toEpochMilli()
        ports.detailsById.value = ports.detailsById.value + (
            id to details(row).let {
                it.copy(note = it.note.copy(recordedAt = null, origin = Origin.IMPORTED_AUDIO, sourceModifiedAt = saved))
            }
            )
    }

    @Test
    fun theRowOpensTheSheetOnTheSavedTimeAndSaveStoresTheStart() = runTest(dispatcher) {
        addImportedClip("c")
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onEvent(LibraryEvent.LongPress("c"))
        advanceUntilIdle()
        viewModel.onEvent(LibraryEvent.SheetAction(NoteAction.SET_DATE_TIME))
        advanceUntilIdle()
        val expected = SetDateTimeUi("c", LocalDate.of(2026, 10, 3), LocalTime.of(11, 18), fromFileSaved = true)
        assertNull("the actions sheet closes", viewModel.now().sheet)
        assertEquals(LibraryDialog.SetDateTime(expected), viewModel.now().dialog)

        // After process death the sheet comes back on the same values.
        val restored = LibraryViewModel(saved, ports, CLOCK, dispatcher)
        backgroundScope.launch { restored.state.collect {} }
        advanceUntilIdle()
        assertEquals(LibraryDialog.SetDateTime(expected), restored.now().dialog)

        viewModel.onEvent(LibraryEvent.DateTimeConfirmed("c", LocalDate.of(2026, 9, 30), LocalTime.of(16, 5)))
        advanceUntilIdle()
        assertNull(viewModel.now().dialog)
        assertEquals("the phone's offset on that day", "startedAt c 2026-09-30T16:05:00+02:00", ports.actions.last())
    }

    @Test
    fun aMeetingWithADateOpensOnItAndCancelStoresNothing() = runTest(dispatcher) {
        ports.add(libraryRow("m", createdAt = daysAgo(1, hour = 15, minute = 10)))
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.onEvent(LibraryEvent.LongPress("m"))
        advanceUntilIdle()
        viewModel.onEvent(LibraryEvent.SheetAction(NoteAction.SET_DATE_TIME))
        advanceUntilIdle()
        assertEquals(
            LibraryDialog.SetDateTime(SetDateTimeUi("m", LocalDate.of(2026, 10, 7), LocalTime.of(15, 10))),
            viewModel.now().dialog,
        )
        viewModel.onEvent(LibraryEvent.DismissDialog)
        advanceUntilIdle()
        assertNull(viewModel.now().dialog)
        assertEquals(emptyList<String>(), ports.actions.filter { it.startsWith("startedAt") })
    }
}
