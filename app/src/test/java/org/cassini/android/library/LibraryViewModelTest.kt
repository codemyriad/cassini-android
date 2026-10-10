package org.cassini.android.library

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.cassini.android.nav.ModelDownloadKey
import org.cassini.android.nav.NoteKey
import org.cassini.android.nav.NoteStart
import org.cassini.android.notices.NoticeFacts
import org.cassini.android.notices.RecoveredNotice
import org.cassini.android.processing.ProcessingState
import org.cassini.android.processing.QueuedNote
import org.cassini.android.processing.TranscribeOutcome
import org.cassini.android.recording.CaptureFormat
import org.cassini.android.recording.RecordingState
import org.cassini.core.audio.RecordingSpace
import org.cassini.core.format.DocumentState
import org.cassini.core.library.AudioKind
import org.cassini.core.library.JobKind
import org.cassini.core.library.JobState
import org.cassini.data.db.SearchRow
import org.cassini.data.settings.QueueHold
import org.cassini.ui.banner.BannerAction
import org.cassini.ui.banner.BannerKind
import org.cassini.ui.components.NoteStatusUi
import org.cassini.ui.components.SnackbarKind
import org.cassini.ui.format.DayLabel
import org.cassini.ui.library.LibraryDialog
import org.cassini.ui.library.LibraryEvent
import org.cassini.ui.library.LibrarySheet
import org.cassini.ui.library.LibraryUiState
import org.cassini.ui.library.RecordingPillUi
import org.cassini.ui.note.ExportKind
import org.cassini.ui.note.NoteAction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The Library ViewModel over fake ports (features.md section 1; ui.md section 7). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LibraryViewModelTest {
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

    @Test
    fun groupsTheLibraryByDayAndShowsTheEmptyBanner() = runTest(dispatcher) {
        val viewModel = viewModel()
        assertEquals(listOf(BannerKind.LIB_EMPTY), viewModel.now().banners.map { it.kind })
        ports.add(libraryRow("a", createdAt = daysAgo(0)))
        ports.add(libraryRow("b", createdAt = daysAgo(1)))
        advanceUntilIdle()
        val state = viewModel.now()
        assertEquals(false, state.loading)
        assertEquals(listOf(DayLabel.Today, DayLabel.Yesterday), state.sections.map { it.label })
        assertTrue(state.banners.isEmpty())
        assertNull(state.results)
    }

    @Test
    fun aCatalogueThatCannotBeReadIsNotAnEmptyLibrary() = runTest(dispatcher) {
        ports.libraryError = java.io.IOException("disk full")
        val viewModel = viewModel()
        advanceUntilIdle()
        val state = viewModel.now()
        assertEquals(listOf(BannerKind.LIB_UNAVAILABLE), state.banners.map { it.kind })
        assertTrue(state.sections.isEmpty())
    }

    @Test
    fun searchesAfterTheDebounceAndKeepsTheQuery() = runTest(dispatcher) {
        ports.add(libraryRow("a"))
        val row = libraryRow("hit", title = "Fattura")
        ports.searchRows.value = mapOf("fatt" to listOf(SearchRow(row, "", "\u0002Fattura\u0003")))
        val viewModel = viewModel()
        viewModel.onEvent(LibraryEvent.Query("fatt"))
        runCurrent()
        assertEquals("fatt", viewModel.now().query)
        assertEquals("fatt", saved.get<String>("query"))
        assertTrue("nothing searched before the debounce", ports.searches.isEmpty())
        advanceTimeBy(LibraryViewModel.SEARCH_DEBOUNCE_MS + 1)
        runCurrent()
        assertEquals(listOf("fatt"), ports.searches)
        val results = viewModel.now().results!!
        assertEquals(listOf("hit"), results.map { it.id })
        assertEquals(listOf(0..6), results[0].titleRanges)
        assertTrue(viewModel.now().sections.isEmpty())

        viewModel.onEvent(LibraryEvent.Query("zzz"))
        advanceUntilIdle()
        assertEquals(BannerKind.LIB_NO_MATCH, viewModel.now().banners.single().kind)
        assertEquals("zzz", viewModel.now().banners.single().args.text)

        viewModel.onEvent(LibraryEvent.ClearQuery)
        advanceUntilIdle()
        assertNull(viewModel.now().results)
        assertEquals(1, viewModel.now().sections.single().rows.size)
    }

    @Test
    fun opensNotesWithTheQueryAndRecording() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.effects.test {
            viewModel.onEvent(LibraryEvent.OpenNote("a"))
            assertEquals(LibraryEffect.OpenNote(NoteKey("a")), awaitItem())
            viewModel.onEvent(LibraryEvent.Query(" giov "))
            viewModel.onEvent(LibraryEvent.OpenNote("b"))
            assertEquals(LibraryEffect.OpenNote(NoteKey("b", "giov")), awaitItem())
            viewModel.onEvent(LibraryEvent.Record)
            assertEquals(LibraryEffect.OpenRecording, awaitItem())
            viewModel.onEvent(LibraryEvent.ReturnToRecording)
            assertEquals(LibraryEffect.OpenRecording, awaitItem())
            viewModel.onEvent(LibraryEvent.Import)
            assertEquals(LibraryEffect.PickAudio, awaitItem())
        }
        viewModel.onPicked("content://picked/1")
        viewModel.onPicked(null)
        assertEquals(listOf("import content://picked/1"), ports.actions)
    }

    @Test
    fun statusAndRunningRowFollowTheCoordinatorAndTheRecorder() = runTest(dispatcher) {
        ports.add(libraryRow("run", jobState = JobState.RUNNING))
        ports.add(libraryRow("rec", audioKind = AudioKind.LIVE_CAPTURE))
        ports.add(docRow("doc", DocumentState.OK, words = 9, speakers = 3))
        ports.recording.value = RecordingState.Active(
            noteId = "rec",
            title = "Standup",
            defaultTitle = "Voice note",
            format = CaptureFormat.OPUS,
            sampleRateHz = 48_000,
            elapsedMs = 1_447_000,
            pause = null,
            space = RecordingSpace(1, 1),
            warning = null,
        )
        ports.processing.value = ProcessingState(loading = false, queue = listOf(QueuedNote("q", "Q")))
        val viewModel = viewModel()
        advanceUntilIdle()
        val rows = viewModel.now().sections.single().rows.associateBy { it.id }
        assertEquals(NoteStatusUi.Transcribing, rows.getValue("run").status)
        assertTrue(rows.getValue("run").highlighted)
        assertEquals(NoteStatusUi.Recording, rows.getValue("rec").status)
        assertEquals(1_447_000L, rows.getValue("rec").durationMs)
        assertEquals(NoteStatusUi.Transcribed(3, 9), rows.getValue("doc").status)
        assertEquals(RecordingPillUi("Standup", 1_447_000, false), viewModel.now().recording)
    }

    @Test
    fun queueHeldBannerContinues() = runTest(dispatcher) {
        ports.add(libraryRow("a"))
        ports.notices.value = NoticeFacts(hold = true, queued = 2)
        ports.processing.value = ProcessingState(loading = false, hold = QueueHold.USER)
        val viewModel = viewModel()
        advanceUntilIdle()
        val banner = viewModel.now().banners.single()
        assertEquals(BannerKind.TX_QUEUE_HELD, banner.kind)
        assertEquals(2, banner.args.count)
        viewModel.onEvent(LibraryEvent.BannerAction(banner.id, BannerAction.CONTINUE))
        advanceUntilIdle()
        assertEquals(listOf("continue"), ports.actions)
    }

    @Test
    fun recoveredBannerOpensTheLatestNoteAndDismisses() = runTest(dispatcher) {
        ports.notices.value = NoticeFacts(recovered = RecoveredNotice(1, "r1", 312_000, 5))
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.effects.test {
            viewModel.onEvent(LibraryEvent.BannerAction(BannerKind.RECOVERED.name, BannerAction.OPEN))
            advanceUntilIdle()
            assertEquals(LibraryEffect.OpenNote(NoteKey("r1")), awaitItem())
        }
        assertEquals(listOf("dismiss RECOVERED"), ports.actions)
        viewModel.onEvent(LibraryEvent.DismissBanner(BannerKind.NO_ENCODER.name))
        assertEquals("dismiss NO_ENCODER", ports.actions.last())
    }

    @Test
    fun resumeWithoutConsentOpensModelDownload() = runTest(dispatcher) {
        val paused = libraryRow("p", jobState = JobState.PAUSED, doneMs = 5)
        ports.add(paused, jobOf("p", JobState.PAUSED, JobKind.TRANSCRIBE_AGAIN))
        ports.transcribeOutcome = TranscribeOutcome.NeedsConsent
        val viewModel = viewModel()
        viewModel.effects.test {
            viewModel.onEvent(LibraryEvent.Resume("p"))
            advanceUntilIdle()
            assertEquals(LibraryEffect.OpenModelDownload(ModelDownloadKey("p")), awaitItem())
        }
        assertEquals(listOf("transcribe p TRANSCRIBE_AGAIN"), ports.actions)
    }

    @Test
    fun trashShowsTheSnackbarAndUndoRestores() = runTest(dispatcher) {
        ports.add(libraryRow("a", title = "Call with supplier"))
        val viewModel = viewModel()
        viewModel.onEvent(LibraryEvent.Trash("a"))
        advanceUntilIdle()
        val snackbar = viewModel.now().snackbar!!
        assertEquals(SnackbarKind.MOVED_TO_TRASH, snackbar.kind)
        assertEquals("Call with supplier", snackbar.text)
        assertTrue(snackbar.undo)
        assertEquals(12_000L, snackbar.durationMs)
        viewModel.onEvent(LibraryEvent.Undo)
        advanceUntilIdle()
        assertNull(viewModel.now().snackbar)
        assertEquals(listOf("trash a", "restore a"), ports.actions)
    }

    @Test
    fun trashOfARunningNoteAsksFirst() = runTest(dispatcher) {
        ports.add(libraryRow("r", jobState = JobState.RUNNING), jobOf("r", JobState.RUNNING))
        val viewModel = viewModel()
        viewModel.onEvent(LibraryEvent.Trash("r"))
        advanceUntilIdle()
        assertEquals(LibraryDialog.StopAndTrash("r"), viewModel.now().dialog)
        assertTrue(ports.actions.isEmpty())
        viewModel.onEvent(LibraryEvent.ConfirmDialog)
        advanceUntilIdle()
        assertNull(viewModel.now().dialog)
        assertEquals(listOf("trash r"), ports.actions)
    }

    @Test
    fun longPressOpensTheActionsSheetAndItsRows() = runTest(dispatcher) {
        ports.add(libraryRow("q", title = "Queued one", jobState = JobState.QUEUED), jobOf("q", JobState.QUEUED))
        val viewModel = viewModel()
        viewModel.onEvent(LibraryEvent.LongPress("q"))
        advanceUntilIdle()
        val sheet = viewModel.now().sheet as LibrarySheet.Actions
        assertEquals("Queued one", sheet.ui.title)
        assertEquals("q", saved.get<String>("sheet"))

        viewModel.onEvent(LibraryEvent.SheetAction(NoteAction.REMOVE_FROM_QUEUE))
        advanceUntilIdle()
        assertNull(viewModel.now().sheet)
        assertEquals(listOf("dequeue q"), ports.actions)

        viewModel.onEvent(LibraryEvent.LongPress("q"))
        advanceUntilIdle()
        viewModel.onEvent(LibraryEvent.SheetAction(NoteAction.RENAME))
        advanceUntilIdle()
        assertEquals(LibraryDialog.Rename("q", "Queued one"), viewModel.now().dialog)
        viewModel.onEvent(LibraryEvent.RenameConfirmed("q", "Renamed"))
        advanceUntilIdle()
        assertNull(viewModel.now().dialog)
        assertEquals("rename q Renamed", ports.actions.last())

        viewModel.onEvent(LibraryEvent.LongPress("q"))
        advanceUntilIdle()
        viewModel.onEvent(LibraryEvent.SheetAction(NoteAction.START_OVER))
        advanceUntilIdle()
        assertEquals(LibraryDialog.StartOver("q"), viewModel.now().dialog)
        viewModel.onEvent(LibraryEvent.ConfirmDialog)
        advanceUntilIdle()
        assertEquals("startOver q", ports.actions.last())
    }

    @Test
    fun exportRowsOpenTheNoteScreenWithTheRowToStart() = runTest(dispatcher) {
        ports.add(docRow("d", DocumentState.OK, words = 3))
        val viewModel = viewModel()
        viewModel.effects.test {
            viewModel.onEvent(LibraryEvent.LongPress("d"))
            advanceUntilIdle()
            viewModel.onEvent(LibraryEvent.SheetAction(NoteAction.SAVE_TEXT))
            assertEquals(LibraryEffect.OpenNote(NoteKey("d", start = NoteStart.SAVE_TEXT)), awaitItem())
            viewModel.onEvent(LibraryEvent.LongPress("d"))
            advanceUntilIdle()
            viewModel.onEvent(LibraryEvent.SheetAction(NoteAction.DOCUMENT_INFO))
            assertEquals(LibraryEffect.OpenNote(NoteKey("d", start = NoteStart.DOCUMENT_INFO)), awaitItem())
            viewModel.onEvent(LibraryEvent.Export("d", ExportKind.VTT))
            assertEquals(LibraryEffect.OpenNote(NoteKey("d", start = NoteStart.SAVE_VTT)), awaitItem())
        }
        advanceUntilIdle()
        assertNull(viewModel.now().sheet)
    }

    @Test
    fun transcribeAgainOnAChangedDocumentConfirmsAFreshOne() = runTest(dispatcher) {
        ports.add(docRow("s", DocumentState.STALE_AUDIO, words = 3))
        val viewModel = viewModel()
        viewModel.onEvent(LibraryEvent.LongPress("s"))
        advanceUntilIdle()
        viewModel.onEvent(LibraryEvent.SheetAction(NoteAction.TRANSCRIBE_AGAIN))
        advanceUntilIdle()
        assertEquals(LibraryDialog.TranscribeFresh("s"), viewModel.now().dialog)
        viewModel.onEvent(LibraryEvent.ConfirmDialog)
        advanceUntilIdle()
        assertEquals(listOf("transcribe s TRANSCRIBE_AGAIN"), ports.actions)
    }

    @Test
    fun theProgressBannerOpensTheRunningNoteOrSettings() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.effects.test {
            viewModel.onEvent(LibraryEvent.OpenProgress)
            advanceUntilIdle()
            assertEquals(LibraryEffect.OpenSettings, awaitItem())
        }
        assertEquals(ProcessingState(loading = false), ports.processing.first())
    }
}
