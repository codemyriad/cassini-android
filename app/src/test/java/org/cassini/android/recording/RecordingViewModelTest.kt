package org.cassini.android.recording

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.cassini.android.nav.ModelDownloadKey
import org.cassini.android.nav.NoteKey
import org.cassini.core.audio.RecordingSpace
import org.cassini.ui.banner.BannerAction
import org.cassini.ui.banner.BannerKind
import org.cassini.ui.recording.RecordingEvent
import org.cassini.ui.recording.RecordingHint
import org.cassini.ui.recording.RecordingPhase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.cassini.ui.recording.CaptureFormat as CaptureFormatUi

/** The Recording ViewModel over a fake recorder (features.md section 2; ui.md section 7). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RecordingViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val ports = FakeRecordingPorts()
    private val saved = SavedStateHandle()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(sdkInt: Int = 34): RecordingViewModel {
        val viewModel = RecordingViewModel(saved, ports, sdkInt)
        backgroundScope.launch { viewModel.state.collect {} }
        runCurrent()
        return viewModel
    }

    private fun active(
        title: String = "Weekly sync",
        pause: RecordingPause? = null,
        warning: RecordingWarning? = null,
        space: RecordingSpace = RecordingSpace(spaceMs = 111_600_000, capMs = 5_700_000),
    ) = RecordingState.Active(
        noteId = "n1",
        title = title,
        defaultTitle = "Voice note 8 Oct, 08:14",
        format = CaptureFormat.OPUS,
        sampleRateHz = 48_000,
        elapsedMs = 1_447_000,
        pause = pause,
        space = space,
        warning = warning,
    )

    @Test
    fun startsOnEntryWithThePermissionAndShowsTheCapture() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onEnter(micGranted = true, notificationsGranted = true)
        advanceUntilIdle()
        assertEquals(listOf("dismissEnded", "start "), ports.actions)
        assertEquals(RecordingPhase.Starting, viewModel.state.value.phase)

        ports.state.value = active(pause = null)
        advanceUntilIdle()
        val state = viewModel.state.value
        assertEquals(RecordingPhase.Recording, state.phase)
        assertEquals("Weekly sync", state.title)
        assertEquals(1_447_000L, state.elapsedMs)
        assertEquals(CaptureFormatUi.OPUS, state.format)
        assertEquals(48_000, state.sampleRateHz)
        assertEquals(RecordingHint.Cap(5_700_000), state.hint)
        assertEquals(56, state.levels.size)
        assertNull(state.banner)

        ports.state.value = active(space = RecordingSpace(spaceMs = 600_000, capMs = 5_700_000))
        advanceUntilIdle()
        assertEquals(RecordingHint.Space(600_000), viewModel.state.value.hint)
    }

    @Test
    fun prefillsTheDefaultTitleOnce() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onEnter(micGranted = true, notificationsGranted = true)
        advanceUntilIdle()
        ports.state.value = active(title = "")
        advanceUntilIdle()
        assertEquals("title Voice note 8 Oct, 08:14", ports.actions.last())
        ports.actions.clear()
        ports.state.value = active(title = "")
        advanceUntilIdle()
        assertTrue("the user may clear the field", ports.actions.isEmpty())
        viewModel.onEvent(RecordingEvent.Title("Standup"))
        assertEquals(listOf("title Standup"), ports.actions)
    }

    @Test
    fun entryDuringARecordingReturnsToIt() = runTest(dispatcher) {
        ports.state.value = active()
        val viewModel = viewModel()
        viewModel.onEnter(micGranted = true, notificationsGranted = true)
        advanceUntilIdle()
        assertTrue(ports.actions.isEmpty())
        assertEquals(RecordingPhase.Recording, viewModel.state.value.phase)
    }

    @Test
    fun asksForThePermissionsAndStartsWhenGranted() = runTest(dispatcher) {
        val viewModel = viewModel(sdkInt = 34)
        viewModel.effects.test {
            viewModel.onEnter(micGranted = false, notificationsGranted = false)
            advanceUntilIdle()
            assertEquals(
                RecordingEffect.RequestPermissions(
                    listOf(RecordingViewModel.RECORD_AUDIO, RecordingViewModel.POST_NOTIFICATIONS),
                ),
                awaitItem(),
            )
        }
        assertEquals(RecordingPhase.NeedsPermission, viewModel.state.value.phase)
        assertEquals(true, saved.get<Boolean>("pendingStart"))
        viewModel.onPermissionResult(micGranted = true, askedNotifications = true)
        advanceUntilIdle()
        assertEquals(listOf("dismissEnded", "start ", "asked"), ports.actions)
    }

    @Test
    fun deniedShowsMicrophoneOffAndStartsOnResumeWithThePermission() = runTest(dispatcher) {
        ports.asked = true
        val viewModel = viewModel(sdkInt = 34)
        viewModel.effects.test {
            viewModel.onEnter(micGranted = false, notificationsGranted = false)
            advanceUntilIdle()
            assertEquals(RecordingEffect.RequestPermissions(listOf(RecordingViewModel.RECORD_AUDIO)), awaitItem())
        }
        viewModel.onPermissionResult(micGranted = false, askedNotifications = false)
        advanceUntilIdle()
        val state = viewModel.state.value
        assertEquals(RecordingPhase.NeedsPermission, state.phase)
        assertEquals(BannerKind.REC_MIC_OFF, state.banner?.kind)
        viewModel.effects.test {
            viewModel.onEvent(RecordingEvent.BannerAction(BannerKind.REC_MIC_OFF.name, BannerAction.OPEN_SETTINGS))
            assertEquals(RecordingEffect.OpenAppSettings, awaitItem())
        }

        viewModel.onResume(micGranted = false)
        assertTrue(ports.actions.isEmpty())
        viewModel.onResume(micGranted = true)
        advanceUntilIdle()
        assertEquals(listOf("dismissEnded", "start "), ports.actions)
        assertNull(viewModel.state.value.banner)
    }

    @Test
    fun belowApi33OnlyTheMicrophoneIsAsked() = runTest(dispatcher) {
        val viewModel = viewModel(sdkInt = 32)
        viewModel.effects.test {
            viewModel.onEnter(micGranted = false, notificationsGranted = false)
            advanceUntilIdle()
            assertEquals(RecordingEffect.RequestPermissions(listOf(RecordingViewModel.RECORD_AUDIO)), awaitItem())
        }
    }

    @Test
    fun pauseResumeFinishAndMinimise() = runTest(dispatcher) {
        ports.state.value = active()
        val viewModel = viewModel()
        viewModel.onEvent(RecordingEvent.PauseResume)
        ports.state.value = active(pause = RecordingPause.USER)
        advanceUntilIdle()
        assertEquals(RecordingPhase.Paused, viewModel.state.value.phase)
        viewModel.onEvent(RecordingEvent.PauseResume)
        viewModel.onEvent(RecordingEvent.Finish)
        assertEquals(listOf("pause", "resume", "finish Weekly sync"), ports.actions)
        viewModel.effects.test {
            viewModel.onEvent(RecordingEvent.Minimise)
            assertEquals(RecordingEffect.Close, awaitItem())
        }
    }

    @Test
    fun otherAppPauseAndWarningsShowTheirBanners() = runTest(dispatcher) {
        ports.state.value = active(pause = RecordingPause.OTHER_APP)
        val viewModel = viewModel()
        advanceUntilIdle()
        assertEquals(BannerKind.REC_PAUSED_OTHER_APP, viewModel.state.value.banner?.kind)
        viewModel.onEvent(RecordingEvent.BannerAction(BannerKind.REC_PAUSED_OTHER_APP.name, BannerAction.RESUME))
        assertEquals(listOf("resume"), ports.actions)
        ports.state.value = active(warning = RecordingWarning.StorageLow(10))
        advanceUntilIdle()
        assertEquals(BannerKind.REC_STORAGE_LOW, viewModel.state.value.banner?.kind)
        ports.state.value = active(warning = RecordingWarning.NearTimeLimit(300_000))
        advanceUntilIdle()
        assertEquals(BannerKind.REC_NEAR_LIMIT, viewModel.state.value.banner?.kind)
    }

    private suspend fun TestScope.finishWith(outcome: RecordingOutcome, noteId: String? = "n1"): RecordingEffect? {
        ports.state.value = active()
        val viewModel = viewModel()
        viewModel.onEnter(micGranted = true, notificationsGranted = true)
        var effect: RecordingEffect? = null
        viewModel.effects.test {
            ports.state.value = RecordingState.Finishing("n1", "Weekly sync", 1_000)
            advanceUntilIdle()
            ports.state.value = RecordingState.Ended(noteId, outcome)
            advanceUntilIdle()
            effect = if (outcome is RecordingOutcome.Saved) awaitItem() else null
            cancelAndIgnoreRemainingEvents()
        }
        return effect
    }

    @Test
    fun finishWithConsentOpensTheNote() = runTest(dispatcher) {
        assertEquals(
            RecordingEffect.ReplaceWith(listOf(NoteKey("n1"))),
            finishWith(RecordingOutcome.Saved(AfterFinish.TRANSCRIBE)),
        )
        assertEquals("dismissEnded", ports.actions.last())
    }

    @Test
    fun finishWithoutConsentOpensModelDownloadOnlyWhenPartsAreMissing() = runTest(dispatcher) {
        ports.missing = true
        assertEquals(
            RecordingEffect.ReplaceWith(listOf(NoteKey("n1"), ModelDownloadKey("n1"))),
            finishWith(RecordingOutcome.Saved(AfterFinish.ASK_CONSENT)),
        )
    }

    @Test
    fun finishWithoutConsentAndNothingMissingOpensTheNote() = runTest(dispatcher) {
        ports.missing = false
        assertEquals(
            RecordingEffect.ReplaceWith(listOf(NoteKey("n1"))),
            finishWith(RecordingOutcome.Saved(AfterFinish.ASK_CONSENT)),
        )
    }

    @Test
    fun finishAfterADeclineOpensTheNoteAudioOnly() = runTest(dispatcher) {
        ports.missing = true
        assertEquals(
            RecordingEffect.ReplaceWith(listOf(NoteKey("n1"))),
            finishWith(RecordingOutcome.Saved(AfterFinish.AUDIO_ONLY)),
        )
    }

    @Test
    fun aStorageStopStaysWithItsBannerAndOpensTheNote() = runTest(dispatcher) {
        ports.state.value = active()
        val viewModel = viewModel()
        ports.state.value = RecordingState.Ended("n1", RecordingOutcome.StoppedForSpace)
        advanceUntilIdle()
        assertEquals(BannerKind.REC_STOPPED_STORAGE, viewModel.state.value.banner?.kind)
        assertEquals(RecordingPhase.Stopping, viewModel.state.value.phase)
        viewModel.effects.test {
            viewModel.onEvent(RecordingEvent.BannerAction(BannerKind.REC_STOPPED_STORAGE.name, BannerAction.OPEN_NOTE))
            assertEquals(RecordingEffect.ReplaceWith(listOf(NoteKey("n1"))), awaitItem())
        }
    }

    @Test
    fun aFailedStartOffersTryAgain() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onEnter(micGranted = true, notificationsGranted = true)
        advanceUntilIdle()
        ports.state.value = RecordingState.Ended(null, RecordingOutcome.CouldNotStart)
        advanceUntilIdle()
        assertEquals(BannerKind.REC_COULD_NOT_START, viewModel.state.value.banner?.kind)
        ports.actions.clear()
        viewModel.onEvent(RecordingEvent.BannerAction(BannerKind.REC_COULD_NOT_START.name, BannerAction.TRY_AGAIN))
        assertEquals(listOf("dismissEnded", "start "), ports.actions)
    }

    @Test
    fun aRestoredEntryWithoutARecordingClosesItself() = runTest(dispatcher) {
        saved["entered"] = true
        val viewModel = viewModel()
        viewModel.effects.test {
            viewModel.onEnter(micGranted = true, notificationsGranted = true)
            assertEquals(RecordingEffect.Close, awaitItem())
        }
        assertTrue(ports.actions.isEmpty())
    }

    @Test
    fun aStaleEndedStateDoesNotLeadAway() = runTest(dispatcher) {
        ports.state.value = RecordingState.Ended("old", RecordingOutcome.Saved(AfterFinish.TRANSCRIBE))
        val viewModel = viewModel()
        viewModel.effects.test {
            viewModel.onEnter(micGranted = true, notificationsGranted = true)
            advanceUntilIdle()
            expectNoEvents()
        }
        assertEquals(listOf("dismissEnded", "start "), ports.actions)
    }
}

/** A recorder that records the calls; [start] moves to Starting as the controller does. */
private class FakeRecordingPorts : RecordingPorts {
    override val state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    override val levels: Flow<List<Float>> = MutableStateFlow(List(56) { 0.5f })
    val actions = mutableListOf<String>()
    var missing = false
    var asked = false

    override fun start(title: String): Boolean {
        actions += "start $title"
        state.value = RecordingState.Starting(title)
        return true
    }

    override fun setTitle(title: String) {
        actions += "title $title"
    }

    override fun pause() {
        actions += "pause"
    }

    override fun resume() {
        actions += "resume"
    }

    override fun finish(title: String?) {
        actions += "finish $title"
    }

    override fun dismissEnded() {
        actions += "dismissEnded"
        if (state.value is RecordingState.Ended) state.value = RecordingState.Idle
    }

    override suspend fun modelsMissing(): Boolean = missing

    override suspend fun lastFormat(): CaptureFormat? = null

    override suspend fun notificationsAsked(): Boolean = asked

    override suspend fun markNotificationsAsked() {
        actions += "asked"
    }
}
