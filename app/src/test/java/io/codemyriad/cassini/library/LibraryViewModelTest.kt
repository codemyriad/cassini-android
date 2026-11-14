package io.codemyriad.cassini.library

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import io.codemyriad.cassini.core.audio.RecordingSpace
import io.codemyriad.cassini.core.format.DocumentState
import io.codemyriad.cassini.core.library.AudioKind
import io.codemyriad.cassini.core.library.JobKind
import io.codemyriad.cassini.core.library.JobState
import io.codemyriad.cassini.data.db.SearchRow
import io.codemyriad.cassini.data.settings.QueueHold
import io.codemyriad.cassini.nav.ModelDownloadKey
import io.codemyriad.cassini.nav.NoteKey
import io.codemyriad.cassini.nav.NoteStart
import io.codemyriad.cassini.notices.NoticeFacts
import io.codemyriad.cassini.notices.RecoveredNotice
import io.codemyriad.cassini.processing.ProcessingState
import io.codemyriad.cassini.processing.QueuedNote
import io.codemyriad.cassini.processing.TranscribeOutcome
import io.codemyriad.cassini.recording.CaptureFormat
import io.codemyriad.cassini.recording.RecordingState
import io.codemyriad.cassini.ui.banner.BannerAction
import io.codemyriad.cassini.ui.banner.BannerKind
import io.codemyriad.cassini.ui.components.NoteStatusUi
import io.codemyriad.cassini.ui.components.SnackbarKind
import io.codemyriad.cassini.ui.format.DayLabel
import io.codemyriad.cassini.ui.library.FirstRunHeroUi
import io.codemyriad.cassini.ui.library.FirstRunPrivacy
import io.codemyriad.cassini.ui.library.LibraryDialog
import io.codemyriad.cassini.ui.library.LibraryEvent
import io.codemyriad.cassini.ui.library.LibrarySheet
import io.codemyriad.cassini.ui.library.LibraryUiState
import io.codemyriad.cassini.ui.library.RecordingPillUi
import io.codemyriad.cassini.ui.note.NoteAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
    fun groupsTheLibraryByDayAndShowsTheFirstRunHeroInsteadOfTheEmptyBanner() = runTest(dispatcher) {
        val viewModel = viewModel()
        assertTrue(viewModel.now().banners.isEmpty())
        assertEquals(FirstRunHeroUi(cloudBackup = false, privacy = FirstRunPrivacy.PROCESSING), viewModel.now().hero)
        ports.add(libraryRow("a", createdAt = daysAgo(0)))
        ports.add(libraryRow("b", createdAt = daysAgo(1)))
        advanceUntilIdle()
        val state = viewModel.now()
        assertEquals(false, state.loading)
        assertEquals(listOf(DayLabel.Today, DayLabel.Yesterday), state.sections.map { it.label })
        assertTrue(state.banners.isEmpty())
        assertNull(state.hero)
        assertNull(state.results)
    }

    @Test
    fun theFirstRunHeroFollowsEveryAutomaticCopyThatCanLeaveThePhone() = runTest(dispatcher) {
        val viewModel = viewModel()
        val expected = mapOf(
            BackupReach(cloud = true, transfer = true) to FirstRunHeroUi(true, FirstRunPrivacy.WITH_BACKUP),
            BackupReach(cloud = true, transfer = false) to FirstRunHeroUi(true, FirstRunPrivacy.WITH_BACKUP),
            BackupReach(cloud = false, transfer = true) to FirstRunHeroUi(false, FirstRunPrivacy.PROCESSING),
            BackupReach(cloud = false, transfer = false) to FirstRunHeroUi(false, FirstRunPrivacy.PHONE_ONLY),
        )
        for ((reach, hero) in expected) {
            ports.backupReach.value = reach
            advanceUntilIdle()
            assertEquals(reach.toString(), hero, viewModel.now().hero)
        }
    }

    @Test
    fun keepOnPhoneOnlyHidesTheCardAndTheHeroThenSaysTheAudioNeverLeaves() = runTest(dispatcher) {
        ports.backupReach.value = BackupReach(cloud = true, transfer = true, card = true)
        val viewModel = viewModel()
        advanceUntilIdle()
        assertTrue(viewModel.now().backupCard)
        assertEquals(FirstRunHeroUi(true, FirstRunPrivacy.WITH_BACKUP), viewModel.now().hero)
        viewModel.onEvent(LibraryEvent.KeepOnPhoneOnly)
        advanceUntilIdle()
        assertTrue("keepOnPhoneOnly" in ports.actions)
        assertFalse(viewModel.now().backupCard)
        assertEquals(FirstRunHeroUi(false, FirstRunPrivacy.PHONE_ONLY), viewModel.now().hero)
    }

    @Test
    fun keepBackupOnHidesTheCardAndKeepsTheBackupCopy() = runTest(dispatcher) {
        ports.backupReach.value = BackupReach(cloud = true, transfer = true, card = true)
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.onEvent(LibraryEvent.DismissBackupCard)
        advanceUntilIdle()
        assertTrue("dismissBackupCard" in ports.actions)
        assertFalse(viewModel.now().backupCard)
        assertEquals(FirstRunHeroUi(true, FirstRunPrivacy.WITH_BACKUP), viewModel.now().hero)
    }

    @Test
    fun theBackupCardIsNotShownWhileSearching() = runTest(dispatcher) {
        ports.backupReach.value = BackupReach(cloud = true, transfer = true, card = true)
        val viewModel = viewModel()
        viewModel.onEvent(LibraryEvent.Query("budget"))
        advanceUntilIdle()
        assertFalse(viewModel.now().backupCard)
    }

    @Test
    fun noPrivacyClaimIsDrawnBeforeTheBackupChoicesAreRead() = runTest(dispatcher) {
        val unread = object : LibraryPorts by ports {
            override val backupReach: Flow<BackupReach> = emptyFlow()
        }
        val viewModel = LibraryViewModel(saved, unread, CLOCK, dispatcher)
        backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()
        assertNull(viewModel.now().hero)
        assertTrue(viewModel.now().banners.none { it.kind == BannerKind.LIB_EMPTY })
    }

    @Test
    fun searchingAnEmptyLibraryShowsNoHero() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onEvent(LibraryEvent.Query("budget"))
        advanceUntilIdle()
        assertNull(viewModel.now().hero)
    }

    @Test
    fun openingAnOverlayKeepsTheMappedRows() = runTest(dispatcher) {
        ports.add(libraryRow("a"))
        val viewModel = viewModel()
        advanceUntilIdle()
        val sections = viewModel.now().sections
        viewModel.onEvent(LibraryEvent.Rename("a"))
        advanceUntilIdle()
        assertTrue(viewModel.now().dialog is LibraryDialog.Rename)
        assertSame(sections, viewModel.now().sections)
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
        val row = libraryRow("hit", title = "Invoice")
        ports.searchRows.value = mapOf("invo" to listOf(SearchRow(row, "", "\u0002Invoice\u0003")))
        val viewModel = viewModel()
        viewModel.onEvent(LibraryEvent.Query("invo"))
        runCurrent()
        assertEquals("invo", viewModel.now().query)
        assertEquals("invo", saved.get<String>("query"))
        assertTrue("nothing searched before the debounce", ports.searches.isEmpty())
        advanceTimeBy(LibraryViewModel.SEARCH_DEBOUNCE_MS + 1)
        runCurrent()
        assertEquals(listOf("invo"), ports.searches)
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
    fun filtersNarrowTheCurrentQueryAndResetOnClearAndProcessDeath() = runTest(dispatcher) {
        val viewModel = viewModel()
        ports.speakerNames.value = listOf("Ada", "Ben")
        viewModel.onEvent(LibraryEvent.Query("delivery"))
        advanceUntilIdle()
        viewModel.onEvent(LibraryEvent.FilterSpeaker("Ada"))
        viewModel.onEvent(LibraryEvent.FilterDate(io.codemyriad.cassini.ui.library.SearchDateUi.WEEK))
        viewModel.onEvent(LibraryEvent.ToggleRecordedByMe)
        advanceUntilIdle()
        val expected = io.codemyriad.cassini.core.library.SearchFilters(
            "Ada",
            io.codemyriad.cassini.core.library.SearchDate.WEEK,
            true,
        )
        assertEquals(expected, ports.filterRequests.last())
        assertEquals(listOf("Ada", "Ben"), viewModel.now().searchFilters.speakers)
        // Only the text is in SavedStateHandle; a new ViewModel gets empty transient filters.
        val restored = viewModel()
        advanceUntilIdle()
        assertEquals("delivery", restored.now().query)
        assertEquals(null, restored.now().searchFilters.speaker)
        assertEquals(false, restored.now().searchFilters.recordedByMe)
        viewModel.onEvent(LibraryEvent.ClearQuery)
        advanceUntilIdle()
        assertNull(viewModel.now().results)
        assertEquals(null, viewModel.now().searchFilters.speaker)
        viewModel.onEvent(LibraryEvent.Query("delivery"))
        advanceUntilIdle()
        assertEquals(io.codemyriad.cassini.core.library.SearchFilters(), ports.filterRequests.last())
    }

    @Test
    fun opensNotesWithTheQueryAndRecording() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.effects.test {
            viewModel.onEvent(LibraryEvent.OpenNote("a"))
            assertEquals(LibraryEffect.OpenNote(NoteKey("a")), awaitItem())
            viewModel.onEvent(LibraryEvent.Query(" caf "))
            viewModel.onEvent(LibraryEvent.OpenNote("b"))
            assertEquals(LibraryEffect.OpenNote(NoteKey("b", "caf")), awaitItem())
            viewModel.onEvent(LibraryEvent.Record)
            assertEquals(LibraryEffect.OpenRecording, awaitItem())
            viewModel.onEvent(LibraryEvent.ReturnToRecording)
            assertEquals(LibraryEffect.OpenRecording, awaitItem())
            viewModel.onEvent(LibraryEvent.Import)
            assertEquals(LibraryEffect.PickAudio, awaitItem())
        }
        viewModel.onPicked(listOf("content://picked/1", "content://picked/2"))
        viewModel.onPicked(emptyList())
        assertEquals(listOf("import content://picked/1", "import content://picked/2"), ports.actions)
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
            space = RecordingSpace(1),
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
            val markdown = listOf(
                NoteAction.SAVE_MARKDOWN to NoteStart.SAVE_MARKDOWN,
                NoteAction.SHARE_MARKDOWN to NoteStart.SHARE_MARKDOWN,
                NoteAction.SAVE_SRT to NoteStart.SAVE_SRT,
                NoteAction.SAVE_VTT to NoteStart.SAVE_VTT,
                NoteAction.SHARE_SRT to NoteStart.SHARE_SRT,
                NoteAction.SHARE_VTT to NoteStart.SHARE_VTT,
                NoteAction.SHARE_TEXT to NoteStart.SHARE_TEXT,
            )
            for ((action, start) in markdown) {
                viewModel.onEvent(LibraryEvent.LongPress("d"))
                advanceUntilIdle()
                viewModel.onEvent(LibraryEvent.SheetAction(action))
                assertEquals(LibraryEffect.OpenNote(NoteKey("d", start = start)), awaitItem())
            }

            viewModel.onEvent(LibraryEvent.LongPress("d"))
            advanceUntilIdle()
            viewModel.onEvent(LibraryEvent.SheetAction(NoteAction.DOCUMENT_INFO))
            assertEquals(LibraryEffect.OpenNote(NoteKey("d", start = NoteStart.DOCUMENT_INFO)), awaitItem())
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
