package org.cassini.android.note

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.cassini.android.library.CLOCK
import org.cassini.android.nav.ModelDownloadKey
import org.cassini.android.nav.NoteKey
import org.cassini.android.nav.NoteStart
import org.cassini.android.notices.TrashedNote
import org.cassini.android.playback.PlaybackState
import org.cassini.android.processing.TranscribeOutcome
import org.cassini.core.format.DocumentState
import org.cassini.core.format.Word
import org.cassini.core.library.AudioKind
import org.cassini.core.library.JobKind
import org.cassini.core.library.JobState
import org.cassini.data.checkpoint.DraftTranscript
import org.cassini.data.db.WordEntity
import org.cassini.data.notes.TranscriptFormat
import org.cassini.engine.job.BatteryLine
import org.cassini.engine.job.DraftState
import org.cassini.engine.job.JobPhase
import org.cassini.engine.job.RunningProgress
import org.cassini.engine.job.Step
import org.cassini.engine.job.TimeLeft
import org.cassini.ui.banner.BannerAction
import org.cassini.ui.banner.BannerKind
import org.cassini.ui.components.SnackbarKind
import org.cassini.ui.components.SpeakerNameUi
import org.cassini.ui.components.WordRef
import org.cassini.ui.format.ProgressHeadline
import org.cassini.ui.note.ExportKind
import org.cassini.ui.note.MergeEvent
import org.cassini.ui.note.NameSheetEvent
import org.cassini.ui.note.NoteAction
import org.cassini.ui.note.NoteDialog
import org.cassini.ui.note.NoteEvent
import org.cassini.ui.note.NoteMode
import org.cassini.ui.note.NoteSheet
import org.cassini.ui.note.NoteUiState
import org.cassini.ui.note.ReadOnlyReason
import org.cassini.ui.note.SaveState
import org.cassini.ui.note.TranscribeRowUi
import org.cassini.ui.note.TrustUi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.cassini.engine.job.ProgressHeadline as EngineHeadline

/** The Note ViewModel over fake ports (features.md sections 3, 4 and 6; ui.md section 7). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Suppress("LargeClass") // one test per Note screen behaviour
class NoteViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val ports = FakeNotePorts()
    private val saved = SavedStateHandle()
    private val effects = mutableListOf<NoteEffect>()
    private val recentWords = RecentWords()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(
        query: String? = null,
        visible: Boolean = true,
        start: NoteStart? = null,
    ): NoteViewModel {
        val viewModel = NoteViewModel(saved, NoteKey(NOTE, query, start), ports, CLOCK, dispatcher, recentWords)
        backgroundScope.launch { viewModel.state.collect {} }
        // Unconfined: advanceUntilIdle does not wait for background work, so the effects are taken at once.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.effects.collect { effects += it } }
        viewModel.onVisible(visible)
        advanceUntilIdle()
        return viewModel
    }

    private fun NoteViewModel.now(): NoteUiState = state.value

    private fun TestScope.send(viewModel: NoteViewModel, vararg events: NoteEvent) {
        for (event in events) {
            viewModel.onEvent(event)
            advanceUntilIdle()
        }
    }

    // Opening. --------------------------------------------------------------------------------------------------

    @Test
    fun aDocumentOpensFromTheCacheWithoutReadingTheFile() = runTest(dispatcher) {
        val viewModel = viewModel()
        val state = viewModel.now()
        assertEquals(NoteMode.Reading, state.mode)
        assertEquals("Weekly sync", state.title)
        assertNull("Read & listen has no subtitle", state.subtitle)
        assertEquals(TrustUi.VERIFIED, state.chips?.trust)
        assertEquals(3, state.chips?.speakers)
        assertNull("one variant: no version chip", state.chips?.version)
        assertEquals(listOf("w0", "w2", "w3", "w5"), state.turns.map { it.id })
        assertEquals(SpeakerNameUi.Placeholder(2), state.turns[1].name)
        assertEquals(setOf("refreshIfStale", "words:0", "openPlayer:document.opus"), ports.calls.toSet())
        assertEquals("one page of words is enough for six words", 1, ports.calls.count { it.startsWith("words") })
        assertFalse("Document info reads the file only when asked", "documentFacts" in ports.calls)
        assertEquals(4_000L, state.playback?.durationMs)
    }

    @Test
    fun theRestOfALongTranscriptLoadsAfterTheFirstTurnsAreShown() = runTest(dispatcher) {
        ports.wordRows.value = List(NoteViewModel.FIRST_PAGE + 50) { i ->
            WordEntity(NOTE, "v1", i, i * 100L, i * 100L + 80, if (i / 30 % 2 == 0) "a" else "c", "w$i")
        }
        val viewModel = NoteViewModel(saved, NoteKey(NOTE), ports, CLOCK, dispatcher, recentWords)
        backgroundScope.launch { viewModel.state.collect {} }
        viewModel.onVisible(true)
        runCurrent()
        assertEquals("the first page only", 1, ports.calls.count { it.startsWith("words") })
        assertFalse("the player waits for the first turns", ports.calls.any { it.startsWith("openPlayer") })
        val firstWords = viewModel.now().turns.sumOf { it.words.size }
        assertEquals(NoteViewModel.FIRST_PAGE, firstWords)

        viewModel.onTurnsShown()
        runCurrent()
        assertEquals(2, ports.calls.count { it.startsWith("words") })
        assertEquals(NoteViewModel.FIRST_PAGE + 50, viewModel.now().turns.sumOf { it.words.size })
        assertTrue(ports.calls.any { it.startsWith("openPlayer") })
    }

    @Test
    fun aLaterOpenOfTheSameVersionReadsNoWords() = runTest(dispatcher) {
        val first = viewModel()
        assertEquals(1, ports.calls.count { it.startsWith("words") })
        val turns = first.now().turns.map { it.id }
        ports.calls.clear()

        val later = viewModel()
        assertEquals(turns, later.now().turns.map { it.id })
        assertFalse("the words come from the process memory", ports.calls.any { it.startsWith("words") })

        // A changed document (a new signature) reads its words again.
        ports.details.value = ports.details.value?.let { it.copy(note = it.note.copy(documentSignature = "1:3:def")) }
        advanceUntilIdle()
        assertTrue(ports.calls.any { it.startsWith("words") })
    }

    @Test
    fun leavingTheScreenLeavesThePlayer() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onVisible(false)
        advanceUntilIdle()
        assertEquals("leavePlayer", ports.calls.last())
        viewModel.onVisible(true)
        advanceUntilIdle()
        assertEquals("openPlayer:document.opus", ports.calls.last())
    }

    @Test
    fun aScreenLeftBeforeItsFirstTurnsDoesNotOpenThePlayer() = runTest(dispatcher) {
        val viewModel = viewModel(visible = false)
        ports.calls.clear()
        viewModel.onVisible(true)
        runCurrent()
        assertFalse("the player waits for the first turns", ports.calls.any { it.startsWith("openPlayer") })

        viewModel.onVisible(false)
        advanceUntilIdle()
        assertFalse("the left screen does not open the player", ports.calls.any { it.startsWith("openPlayer") })
        assertFalse("no player was open, so none is left", "leavePlayer" in ports.calls)

        viewModel.onVisible(true)
        viewModel.onTurnsShown()
        advanceUntilIdle()
        assertEquals(1, ports.calls.count { it.startsWith("openPlayer") })
    }

    // Modes. ----------------------------------------------------------------------------------------------------

    @Test
    fun theModeFollowsTheJobAndTheDocument() = runTest(dispatcher) {
        val viewModel = viewModel()
        ports.setJob(job(JobState.QUEUED, JobKind.TRANSCRIBE_AGAIN), ahead = 2)
        advanceUntilIdle()
        assertEquals(NoteMode.Queued(2, overDocument = true), viewModel.now().mode)
        assertEquals("the sealed transcript stays under the Queued card", 4, viewModel.now().turns.size)
        assertNotNull(viewModel.now().subtitle)

        val draft = DraftState(
            words = listOf(Word("s1", 0, 400, "Prima"), Word("s2", 500, 900, "bozza")),
            labels = emptyMap(),
            automatic = emptySet(),
            suggestions = emptyList(),
            tentativeFromMs = 600,
            namingOpen = true,
        )
        ports.setJob(job(JobState.RUNNING), phase = JobPhase.Transcribing(progress(), draft))
        advanceUntilIdle()
        val running = viewModel.now()
        val mode = running.mode as NoteMode.Processing
        assertEquals(ProgressHeadline.FINDING_WORDS, mode.card.headline)
        assertEquals(40, mode.card.percent)
        val texts = running.turns.flatMap { turn -> turn.words.map { it.text } }
        assertEquals("the draft replaces the sealed transcript", listOf("Prima", "bozza"), texts)
        assertEquals("the second word ends after tentativeFromMs", 0, running.turns[1].unsettledFrom)
        assertNull("Transcribing has no player", running.playback)
        assertNull(running.chips)
        assertEquals("leavePlayer", ports.calls.last())

        ports.draft = DraftTranscript(listOf(Word("s1", 0, 400, "Fermato")), emptyMap(), ports.names.value, 400, 4_000)
        ports.setJob(job(JobState.PAUSED, doneMs = 400))
        advanceUntilIdle()
        assertEquals(NoteMode.Paused(400, 4_000, hasCheckpoint = true), viewModel.now().mode)
        assertEquals("Fermato", viewModel.now().turns.single().words.single().text)
        assertNotNull("Paused has the player", viewModel.now().playback)
        assertTrue(viewModel.now().banners.any { it.kind == BannerKind.TX_PAUSED })

        ports.setJob(null)
        advanceUntilIdle()
        assertEquals(NoteMode.Reading, viewModel.now().mode)
        assertEquals(4, viewModel.now().turns.size)
    }

    @Test
    fun aPlainNoteIsAudioOnlyAndAnUnplayableOneHasNoPlayer() = runTest(dispatcher) {
        ports.details.value = documentNote(audioKind = AudioKind.PLAIN)
        val viewModel = viewModel()
        assertEquals(NoteMode.AudioOnly, viewModel.now().mode)
        assertEquals(listOf(BannerKind.PLAIN_AUDIO), viewModel.now().banners.map { it.kind })
        assertTrue(viewModel.now().turns.isEmpty())
        assertFalse("a plain note has no word cache", ports.calls.any { it.startsWith("words") })

        ports.details.value = documentNote(audioKind = AudioKind.UNPLAYABLE)
        advanceUntilIdle()
        assertEquals(NoteMode.Unplayable, viewModel.now().mode)
        assertNull(viewModel.now().playback)
        assertEquals(listOf(BannerKind.UNPLAYABLE), viewModel.now().banners.map { it.kind })
    }

    // Playback. -------------------------------------------------------------------------------------------------

    @Test
    fun theActiveWordFollowsThePlayerPosition() = runTest(dispatcher) {
        val viewModel = viewModel()
        assertEquals(WordRef("w0", 0), viewModel.now().activeWord)
        ports.playback.value = PlaybackState(noteId = NOTE, durationMs = 4_000, positionMs = 1_100, playing = true)
        advanceUntilIdle()
        assertEquals(WordRef("w2", 0), viewModel.now().activeWord)
        ports.playback.value = ports.playback.value.copy(positionMs = 2_500)
        advanceUntilIdle()
        assertEquals("between words the last started word stays active", WordRef("w3", 1), viewModel.now().activeWord)
        assertTrue(viewModel.now().playback!!.playing)

        ports.playback.value = PlaybackState(noteId = "another note", positionMs = 3_500)
        advanceUntilIdle()
        assertNull("another note's player marks nothing", viewModel.now().activeWord)
    }

    @Test
    fun aWordTapSeeksAndPlaysAndFollowModeResumes() = runTest(dispatcher) {
        val viewModel = viewModel()
        send(viewModel, NoteEvent.PauseFollowing)
        assertFalse(viewModel.now().playback!!.following)
        send(viewModel, NoteEvent.WordTap(WordRef("w3", 1)))
        assertEquals(listOf("seek:2400", "play"), ports.calls.takeLast(2))
        assertTrue(viewModel.now().playback!!.following)
        assertEquals(WordRef("w3", 1), viewModel.now().activeWord)

        send(viewModel, NoteEvent.Seek(3_200))
        assertEquals("the slider shows the drag", 3_200, viewModel.now().playback!!.positionMs)
        send(viewModel, NoteEvent.SeekFinished, NoteEvent.Skip(forward = false))
        assertEquals(listOf("seek:3200", "skip:-10000"), ports.calls.takeLast(2))
    }

    // Search. ---------------------------------------------------------------------------------------------------

    @Test
    fun searchFromTheLibraryIsPrefilledAndStepsThroughMatches() = runTest(dispatcher) {
        val viewModel = viewModel(query = " giovedi ")
        val search = viewModel.now().search!!
        assertEquals("giovedi", search.query)
        assertEquals("giovedì and giovedi", listOf("w0", "w3"), search.matches.map { it.turnId })
        assertEquals(0, search.current)
        send(viewModel, NoteEvent.NextMatch)
        assertEquals(1, viewModel.now().search!!.current)
        send(viewModel, NoteEvent.NextMatch)
        assertEquals("it wraps around", 0, viewModel.now().search!!.current)
        send(viewModel, NoteEvent.PreviousMatch)
        assertEquals(1, viewModel.now().search!!.current)

        send(viewModel, NoteEvent.SearchQuery("BUONGIORNO"))
        assertEquals(listOf("w2"), viewModel.now().search!!.matches.map { it.turnId })
        send(viewModel, NoteEvent.SearchQuery("domani"))
        assertEquals(0, viewModel.now().search!!.total)
        send(viewModel, NoteEvent.CloseSearch)
        assertNull(viewModel.now().search)
    }

    // Exports. --------------------------------------------------------------------------------------------------

    @Test
    fun transcriptExportsUseTheDisplayLabelsOfTheSelectedVersion() = runTest(dispatcher) {
        val viewModel = viewModel()
        send(viewModel, NoteEvent.More, NoteEvent.SheetAction(NoteAction.SAVE_TEXT))
        assertEquals(NoteEffect.CreateFile("text/plain", "Weekly sync.txt"), effects.last())
        viewModel.onExportTarget("content://picked/1")
        advanceUntilIdle()
        val request = ports.exports.single()
        assertEquals(TranscriptFormat.TXT, request.format)
        assertEquals("v1", request.variantId)
        assertEquals(mapOf("a" to "Giulia", "b" to "Speaker 2", "c" to "Paolo"), request.labels)
        assertEquals("8 Oct 2026 · 3", request.subtitle)
        assertEquals("No words", request.emptyText)
        val snackbar = viewModel.now().snackbar!!
        assertEquals(SnackbarKind.SAVED_FILE, snackbar.kind)
        assertEquals("Weekly sync (1).txt", snackbar.text)
        assertNull("the sheet closes after the export", viewModel.now().sheet)

        send(viewModel, NoteEvent.More, NoteEvent.SheetAction(NoteAction.SAVE_SUBTITLES))
        assertEquals(NoteSheet.SubtitleFormat, viewModel.now().sheet)
        send(viewModel, NoteEvent.Export(ExportKind.VTT))
        assertEquals(NoteEffect.CreateFile("text/vtt", "Weekly sync.vtt"), effects.last())
        viewModel.onExportTarget(null)
        advanceUntilIdle()
        assertEquals("a cancelled picker writes nothing", 1, ports.exports.size)

        send(viewModel, NoteEvent.More, NoteEvent.SheetAction(NoteAction.SAVE_DOCUMENT))
        assertEquals(NoteEffect.CreateFile("audio/ogg", "Weekly sync.opus"), effects.last())
        viewModel.onExportTarget("content://picked/2")
        advanceUntilIdle()
        assertEquals("exportCopy:content://picked/2", ports.calls.last())
    }

    @Test
    fun aPlainNoteSavesItsAudioAndHasNoTextExports() = runTest(dispatcher) {
        ports.details.value = documentNote(audioKind = AudioKind.PLAIN)
        val viewModel = viewModel()
        send(viewModel, NoteEvent.More)
        val actions = (viewModel.now().sheet as NoteSheet.Actions).ui
        assertFalse(actions.textExportEnabled)
        assertEquals(TranscribeRowUi.Transcribe, actions.transcribeRow)
        send(viewModel, NoteEvent.SheetAction(NoteAction.SAVE_TEXT))
        assertTrue(effects.none { it is NoteEffect.CreateFile })
        send(viewModel, NoteEvent.SheetAction(NoteAction.SAVE_DOCUMENT))
        assertEquals(NoteEffect.CreateFile("audio/ogg", "Weekly sync.opus"), effects.last())
    }

    @Test
    fun theExportAfterProcessDeathStillRuns() = runTest(dispatcher) {
        saved["export"] = ExportKind.SRT.name
        val viewModel = viewModel()
        viewModel.onExportTarget("content://picked/3")
        advanceUntilIdle()
        assertEquals(TranscriptFormat.SRT, ports.exports.single().format)
    }

    @Test
    fun aRowStartedFromTheLibraryRunsOnceOnly() = runTest(dispatcher) {
        viewModel(start = NoteStart.SAVE_SRT)
        assertEquals(listOf<NoteEffect>(NoteEffect.CreateFile("application/x-subrip", "Weekly sync.srt")), effects)
        assertEquals(true, saved.get<Boolean>("started"))
        effects.clear()
        viewModel(start = NoteStart.SAVE_SRT)
        assertTrue("a restored screen does not ask again", effects.isEmpty())
    }

    @Test
    fun documentInfoCanStartFromTheLibrary() = runTest(dispatcher) {
        val viewModel = viewModel(start = NoteStart.DOCUMENT_INFO)
        assertTrue(viewModel.now().sheet is NoteSheet.Info)
        assertTrue("documentFacts" in ports.calls)
    }

    // Trash. ----------------------------------------------------------------------------------------------------

    @Test
    fun moveToTrashGoesBackToTheLibraryWithUndo() = runTest(dispatcher) {
        val viewModel = viewModel()
        send(viewModel, NoteEvent.More, NoteEvent.SheetAction(NoteAction.MOVE_TO_TRASH))
        assertTrue("moveToTrash" in ports.calls)
        assertEquals(listOf(TrashedNote(NOTE, "Weekly sync")), ports.undo)
        assertEquals("one navigation only", listOf(NoteEffect.CloseToLibrary), effects)
        assertTrue("leavePlayer" in ports.calls)
    }

    @Test
    fun aRunningJobAsksBeforeMoveToTrash() = runTest(dispatcher) {
        val viewModel = viewModel()
        ports.setJob(job(JobState.RUNNING), phase = JobPhase.Starting)
        advanceUntilIdle()
        send(viewModel, NoteEvent.More, NoteEvent.SheetAction(NoteAction.MOVE_TO_TRASH))
        assertEquals(NoteDialog.StopAndTrash, viewModel.now().dialog)
        assertFalse("moveToTrash" in ports.calls)
        send(viewModel, NoteEvent.ConfirmDialog)
        assertTrue("moveToTrash" in ports.calls)
        assertEquals(listOf(NoteEffect.CloseToLibrary), effects)
    }

    @Test
    fun aNoteTrashedElsewhereClosesTheScreen() = runTest(dispatcher) {
        viewModel()
        ports.details.value = ports.details.value!!.let { it.copy(note = it.note.copy(trashedAt = 9)) }
        advanceUntilIdle()
        assertEquals(listOf(NoteEffect.Close), effects)
    }

    // Edits during a job. ---------------------------------------------------------------------------------------

    @Test
    fun aJobRowMakesTheSealedTranscriptReadOnly() = runTest(dispatcher) {
        ports.details.value = documentNote(variants = 2)
        val viewModel = viewModel()
        ports.setJob(job(JobState.QUEUED, JobKind.TRANSCRIBE_AGAIN))
        advanceUntilIdle()
        assertEquals(ReadOnlyReason.JOB_PENDING, viewModel.now().readOnlyReason)

        send(viewModel, NoteEvent.SpeakerTap("b"))
        assertEquals(NoteDialog.ReadOnly(ReadOnlyReason.JOB_PENDING), viewModel.now().dialog)
        assertNull("no Name sheet", viewModel.now().sheet)
        send(viewModel, NoteEvent.DismissDialog, NoteEvent.OpenVersions)
        assertEquals(NoteDialog.ReadOnly(ReadOnlyReason.JOB_PENDING), viewModel.now().dialog)
        send(viewModel, NoteEvent.DismissDialog, NoteEvent.AcceptSuggestion("b", "anna"))
        assertEquals(NoteDialog.ReadOnly(ReadOnlyReason.JOB_PENDING), viewModel.now().dialog)
        assertTrue("nothing was written", ports.relabels.isEmpty())
        assertFalse(ports.calls.any { it.startsWith("enrol") || it.startsWith("merge") || it.startsWith("select") })

        send(viewModel, NoteEvent.DismissDialog, NoteEvent.More)
        val actions = (viewModel.now().sheet as NoteSheet.Actions).ui
        assertEquals("no Transcribe again while queued", TranscribeRowUi.RemoveFromQueue, actions.transcribeRow)

        send(viewModel, NoteEvent.DismissSheet, NoteEvent.More, NoteEvent.SheetAction(NoteAction.RENAME))
        assertEquals("the title can always be renamed", NoteDialog.Rename("Weekly sync"), viewModel.now().dialog)
        send(viewModel, NoteEvent.RenameConfirmed("Sync"))
        assertEquals("rename:Sync", ports.calls.last())
    }

    @Test
    fun aDocumentThatIsNotOkCannotBeRenamedAndTranscribesFresh() = runTest(dispatcher) {
        ports.details.value = documentNote(state = DocumentState.STALE_AUDIO)
        val viewModel = viewModel()
        assertEquals(ReadOnlyReason.TRUST, viewModel.now().readOnlyReason)
        assertEquals(listOf(BannerKind.TRUST_AUDIO_CHANGED), viewModel.now().banners.map { it.kind })
        send(viewModel, NoteEvent.SpeakerTap("a"))
        assertEquals(NoteDialog.ReadOnly(ReadOnlyReason.TRUST), viewModel.now().dialog)

        val transcribeAgain = NoteEvent.BannerAction("TRUST_AUDIO_CHANGED", BannerAction.TRANSCRIBE_AGAIN)
        send(viewModel, NoteEvent.DismissDialog, transcribeAgain)
        assertEquals(NoteDialog.TranscribeFresh, viewModel.now().dialog)
        send(viewModel, NoteEvent.ConfirmDialog)
        assertEquals("transcribe:TRANSCRIBE_AGAIN", ports.calls.last())
    }

    @Test
    fun theTrustChipTogglesTheBannerAndPlayAnywayAcknowledges() = runTest(dispatcher) {
        val viewModel = viewModel()
        assertTrue(viewModel.now().banners.isEmpty())
        send(viewModel, NoteEvent.TrustChip)
        assertEquals(listOf(BannerKind.TRUST_VERIFIED), viewModel.now().banners.map { it.kind })
        send(viewModel, NoteEvent.TrustChip)
        assertTrue(viewModel.now().banners.isEmpty())

        ports.details.value = documentNote(state = DocumentState.UNVERIFIED)
        advanceUntilIdle()
        assertEquals(listOf(BannerKind.TRUST_NOT_VERIFIED), viewModel.now().banners.map { it.kind })
        send(viewModel, NoteEvent.BannerAction("TRUST_NOT_VERIFIED", BannerAction.PLAY_ANYWAY))
        assertEquals(listOf("acknowledge", "play"), ports.calls.takeLast(2))
    }

    // Naming. ---------------------------------------------------------------------------------------------------

    @Test
    fun theNameSheetAsksAboutTheSamePersonThenEnrolsANewVoice() = runTest(dispatcher) {
        val viewModel = viewModel()
        send(viewModel, NoteEvent.SpeakerTap("b"))
        val open = (viewModel.now().sheet as NoteSheet.NameSpeaker).ui
        assertEquals(SpeakerNameUi.Placeholder(2), open.label)
        assertEquals("", open.name)
        assertTrue(open.remember)

        send(viewModel, NoteEvent.NameSheet(NameSheetEvent.NameChanged("Anna")))
        val asking = (viewModel.now().sheet as NoteSheet.NameSpeaker).ui
        assertEquals("Anna Ricci", asking.samePerson)
        assertEquals(SaveState.BLOCKED_BY_QUESTION, asking.save)
        send(viewModel, NoteEvent.NameSheet(NameSheetEvent.SaveBlocked))
        assertTrue("Save with the question open saves nothing", ports.relabels.isEmpty())
        assertNotNull(viewModel.now().sheet)

        send(viewModel, NoteEvent.NameSheet(NameSheetEvent.SomeoneElse), NoteEvent.NameSheet(NameSheetEvent.Save))
        assertNull(viewModel.now().sheet)
        assertEquals(listOf(mapOf("b" to "Anna")), ports.relabels)
        assertTrue("enrol:b:null:Anna" in ports.calls)
    }

    @Test
    fun aSealedMergeAddsAVersionAndCarriesThePrints() = runTest(dispatcher) {
        val viewModel = viewModel()
        send(viewModel, NoteEvent.SpeakerTap("b"), NoteEvent.NameSheet(NameSheetEvent.MergeInto))
        val merge = (viewModel.now().sheet as NoteSheet.Merge).ui
        assertEquals(listOf("a", "c"), merge.targets.map { it.speakerId })
        send(viewModel, NoteEvent.Merge(MergeEvent.PickTarget("a")))
        assertEquals("a", (viewModel.now().sheet as NoteSheet.Merge).ui.confirm?.speakerId)
        send(viewModel, NoteEvent.Merge(MergeEvent.Confirm))
        assertTrue("merge:b>a" in ports.calls)
        assertTrue("copyPrints:v1>v2-b" in ports.calls)
        assertNull(viewModel.now().sheet)
    }

    @Test
    fun aDraftSpeakerIsNamedThroughTheDraftNames() = runTest(dispatcher) {
        val viewModel = viewModel()
        val draft = DraftState(
            words = listOf(Word("s1", 0, 5_000, "Lunga"), Word("s2", 5_000, 6_000, "frase")),
            labels = emptyMap(),
            automatic = emptySet(),
            suggestions = emptyList(),
            tentativeFromMs = 6_000,
            namingOpen = true,
        )
        ports.setJob(job(JobState.RUNNING), phase = JobPhase.Transcribing(progress(), draft))
        advanceUntilIdle()
        send(viewModel, NoteEvent.SpeakerTap("s1"))
        val sheet = (viewModel.now().sheet as NoteSheet.NameSpeaker).ui
        assertTrue(sheet.draft)
        assertFalse("Transcribing has no player for the sample", sheet.canSample)
        send(
            viewModel,
            NoteEvent.NameSheet(NameSheetEvent.NameChanged("Luca")),
            NoteEvent.NameSheet(NameSheetEvent.Save),
        )
        assertEquals(listOf("draft.rename:s1:Luca", "draft.remember:s1:"), ports.calls.takeLast(2))
        assertTrue("a draft never rewrites the document", ports.relabels.isEmpty())

        ports.setJob(job(JobState.RUNNING), phase = JobPhase.Transcribing(progress(), draft.copy(namingOpen = false)))
        advanceUntilIdle()
        assertEquals(ReadOnlyReason.SAVING, viewModel.now().readOnlyReason)
        send(viewModel, NoteEvent.SpeakerTap("s1"))
        assertEquals(NoteDialog.ReadOnly(ReadOnlyReason.SAVING), viewModel.now().dialog)
    }

    // Queue actions. --------------------------------------------------------------------------------------------

    @Test
    fun theFirstTranscribeAsksForNotificationsOnce() = runTest(dispatcher) {
        ports.details.value = documentNote(audioKind = AudioKind.PLAIN)
        ports.askNotifications = true
        val viewModel = viewModel()
        send(viewModel, NoteEvent.BannerAction("PLAIN_AUDIO", BannerAction.TRANSCRIBE))
        assertEquals(listOf<NoteEffect>(NoteEffect.RequestNotifications), effects)
        assertEquals(listOf("markAsked", "transcribe:TRANSCRIBE"), ports.calls.takeLast(2))
        send(viewModel, NoteEvent.BannerAction("PLAIN_AUDIO", BannerAction.TRANSCRIBE))
        assertEquals("asked once", 1, effects.count { it == NoteEffect.RequestNotifications })

        ports.transcribeOutcome = TranscribeOutcome.NeedsConsent
        send(viewModel, NoteEvent.BannerAction("PLAIN_AUDIO", BannerAction.TRANSCRIBE))
        assertEquals(NoteEffect.OpenModelDownload(ModelDownloadKey(NOTE)), effects.last())
    }

    @Test
    fun sheetsAndDialogsComeBackAfterProcessDeath() = runTest(dispatcher) {
        val first = viewModel()
        send(first, NoteEvent.More)
        assertTrue(first.now().sheet is NoteSheet.Actions)
        assertTrue("a new ViewModel over the same saved state", viewModel().now().sheet is NoteSheet.Actions)

        send(first, NoteEvent.SheetAction(NoteAction.RENAME))
        assertEquals(NoteDialog.Rename("Weekly sync"), first.now().dialog)
        val restored = viewModel()
        assertEquals(NoteDialog.Rename("Weekly sync"), restored.now().dialog)
        assertNull(restored.now().sheet)
    }

    @Test
    fun anUnverifiedDocumentOffersNoTranscribeAgainAndItsFailureClears() = runTest(dispatcher) {
        ports.details.value = documentNote(DocumentState.UNVERIFIED)
        val viewModel = viewModel()
        ports.setJob(job(JobState.FAILED, JobKind.TRANSCRIBE_AGAIN, failure = "DOCUMENT_UNVERIFIED"))
        advanceUntilIdle()
        send(viewModel, NoteEvent.More)
        val row = (viewModel.now().sheet as NoteSheet.Actions).ui.transcribeRow
        assertEquals(TranscribeRowUi.TranscribeAgainUnavailable, row)
        send(viewModel, NoteEvent.SheetAction(NoteAction.TRANSCRIBE_AGAIN))
        assertFalse(ports.calls.any { it.startsWith("transcribe") })

        val failed = viewModel.now().banners.single { it.kind == BannerKind.TX_FAILED }
        assertEquals("failed: DOCUMENT_UNVERIFIED", failed.args.text)
        assertTrue("no Try again: it fails the same way", failed.actions.isEmpty())
        send(viewModel, NoteEvent.DismissBanner(failed.id))
        assertEquals("clearFailed", ports.calls.last())
    }

    @Test
    fun pausedRowsResumeOrStartOverWithConfirmation() = runTest(dispatcher) {
        val viewModel = viewModel()
        ports.setJob(job(JobState.PAUSED, JobKind.TRANSCRIBE_AGAIN, doneMs = 1_000))
        advanceUntilIdle()
        send(viewModel, NoteEvent.More)
        assertEquals(TranscribeRowUi.ResumeOrStartOver, (viewModel.now().sheet as NoteSheet.Actions).ui.transcribeRow)
        send(viewModel, NoteEvent.SheetAction(NoteAction.RESUME))
        assertEquals("transcribe:TRANSCRIBE_AGAIN", ports.calls.last())
        send(viewModel, NoteEvent.More, NoteEvent.SheetAction(NoteAction.START_OVER))
        assertEquals(NoteDialog.StartOver, viewModel.now().dialog)
        assertFalse("startOver" in ports.calls)
        send(viewModel, NoteEvent.ConfirmDialog)
        assertEquals("startOver", ports.calls.last())

        ports.setJob(job(JobState.QUEUED))
        advanceUntilIdle()
        send(viewModel, NoteEvent.RemoveFromQueue)
        assertEquals("dequeue", ports.calls.last())
    }

    @Test
    fun documentInfoReadsTheDocumentFactsWhenOpened() = runTest(dispatcher) {
        val viewModel = viewModel()
        send(viewModel, NoteEvent.More, NoteEvent.SheetAction(NoteAction.DOCUMENT_INFO))
        val info = (viewModel.now().sheet as NoteSheet.Info).ui
        assertEquals(1_234, info.fileBytes)
        assertEquals("Parakeet", info.model)
        assertEquals(3, info.speakers)
        assertEquals(10.0, info.realtimeFactor!!, 0.001)
    }

    private fun progress() = RunningProgress(
        headline = EngineHeadline.FINDING_WORDS,
        rangeStartMs = 0,
        rangeEndMs = 1_000,
        percent = 40,
        step = Step.WORDS_AND_VOICES,
        matchingSkipped = false,
        timeLeft = TimeLeft.Calculating,
        doneMs = 1_600,
        totalMs = 4_000,
        battery = BatteryLine.Unavailable,
        words = 2,
        lowMemory = false,
    )
}
