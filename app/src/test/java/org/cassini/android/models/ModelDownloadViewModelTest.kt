package org.cassini.android.models

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.cassini.android.processing.TranscribeOutcome
import org.cassini.core.models.ModelPart
import org.cassini.core.models.ModelPlan
import org.cassini.ui.banner.BannerAction
import org.cassini.ui.banner.BannerKind
import org.cassini.ui.format.ModelPartUi
import org.cassini.ui.models.ModelDownloadEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The Model download ViewModel over fake ports (features.md section 5). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ModelDownloadViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val ports = FakeModelDownloadPorts()
    private val effects = mutableListOf<ModelDownloadEffect>()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(noteId: String? = NOTE): ModelDownloadViewModel {
        val viewModel = ModelDownloadViewModel(noteId, ports)
        backgroundScope.launch { viewModel.state.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.effects.collect { effects += it } }
        advanceUntilIdle()
        return viewModel
    }

    @Test
    fun showsTheMissingPartsWithTheirSizesAndTheSpaceTheInstallNeeds() = runTest(dispatcher) {
        val state = viewModel().state.value!!
        assertEquals(listOf(ModelPartUi.SPEECH, ModelPartUi.SPEAKERS, ModelPartUi.VOICES), state.parts.map { it.part })
        assertEquals(FRESH.missing.map { it.downloadBytes }, state.parts.map { it.bytes })
        assertEquals(FRESH.requiredFreeBytes, state.requiredFreeBytes)
        assertTrue(state.withNote)
        assertNull(state.banner)
        assertFalse(viewModel(noteId = null).state.value!!.withNote)
    }

    @Test
    fun aStaleEntryQueuesItsNoteAndPopsItself() = runTest(dispatcher) {
        ports.plan = ModelPlan(emptyList())
        val viewModel = viewModel()
        assertNull(viewModel.state.value)
        assertEquals(listOf(NOTE), ports.transcribed)
        assertEquals(listOf<ModelDownloadEffect>(ModelDownloadEffect.Close), effects)
        assertEquals(0, ports.consents)
    }

    @Test
    fun aStaleEntryWithoutANoteOnlyPops() = runTest(dispatcher) {
        ports.plan = ModelPlan(emptyList())
        viewModel(noteId = null)
        assertTrue(ports.transcribed.isEmpty())
        assertEquals(listOf<ModelDownloadEffect>(ModelDownloadEffect.Close), effects)
    }

    @Test
    fun downloadAndTranscribeRecordsConsentQueuesTheNoteAndGoesBack() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onEvent(ModelDownloadEvent.Download)
        advanceUntilIdle()
        assertEquals(1, ports.consents)
        assertEquals(listOf(NOTE), ports.transcribed)
        assertTrue(ports.downloads.isEmpty())
        assertEquals(listOf<ModelDownloadEffect>(ModelDownloadEffect.Close), effects)
    }

    @Test
    fun downloadWithoutANoteStartsTheStandaloneDownloadOfTheMissingParts() = runTest(dispatcher) {
        ports.plan = ModelPlan(FRESH.missing.drop(1))
        val viewModel = viewModel(noteId = null)
        viewModel.onEvent(ModelDownloadEvent.Download)
        advanceUntilIdle()
        assertEquals(1, ports.consents)
        assertEquals(listOf(setOf(ModelPart.SPEAKERS, ModelPart.VOICES)), ports.downloads)
        assertTrue(ports.transcribed.isEmpty())
        assertEquals(listOf<ModelDownloadEffect>(ModelDownloadEffect.Close), effects)
    }

    @Test
    fun notEnoughSpaceShowsTheBannerAndRecordsNoConsentUntilRetryFits() = runTest(dispatcher) {
        ports.usable = FRESH.requiredFreeBytes - 1
        val viewModel = viewModel()
        viewModel.onEvent(ModelDownloadEvent.Download)
        advanceUntilIdle()
        val banner = viewModel.state.value!!.banner!!
        assertEquals(BannerKind.DL_NO_SPACE, banner.kind)
        assertEquals(FRESH.requiredFreeBytes, banner.args.bytes)
        assertEquals(listOf(BannerAction.RETRY), banner.actions)
        assertFalse(viewModel.state.value!!.busy)
        assertEquals(0, ports.consents)
        assertTrue(effects.isEmpty())

        ports.usable = FRESH.requiredFreeBytes
        viewModel.onEvent(ModelDownloadEvent.BannerAction(banner.id, BannerAction.RETRY))
        advanceUntilIdle()
        assertEquals(1, ports.consents)
        assertEquals(listOf(NOTE), ports.transcribed)
        assertEquals(listOf<ModelDownloadEffect>(ModelDownloadEffect.Close), effects)
    }

    @Test
    fun asksForNotificationsOnceBeforeTheFirstDownload() = runTest(dispatcher) {
        ports.askNotifications = true
        val viewModel = viewModel()
        viewModel.onEvent(ModelDownloadEvent.Download)
        advanceUntilIdle()
        assertEquals(listOf<ModelDownloadEffect>(ModelDownloadEffect.RequestNotifications), effects)
        assertTrue(ports.notificationsMarked)
        assertEquals(0, ports.consents)
        assertTrue(viewModel.state.value!!.busy)

        viewModel.onNotificationsAnswered()
        advanceUntilIdle()
        assertEquals(1, ports.consents)
        assertEquals(listOf(NOTE), ports.transcribed)
        assertEquals(ModelDownloadEffect.Close, effects.last())
        // A second answer (a recreated route) starts nothing again.
        viewModel.onNotificationsAnswered()
        advanceUntilIdle()
        assertEquals(1, ports.consents)
    }

    @Test
    fun notNowRecordsTheRefusalAndGoesBackWithoutConsent() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onEvent(ModelDownloadEvent.NotNow)
        advanceUntilIdle()
        assertEquals(1, ports.declines)
        assertEquals(0, ports.consents)
        assertTrue(ports.transcribed.isEmpty())
        assertEquals(listOf<ModelDownloadEffect>(ModelDownloadEffect.Close), effects)
    }

    @Test
    fun closeOnlyPopsAndSourcesOpenLicenses() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onEvent(ModelDownloadEvent.OpenSources)
        viewModel.onEvent(ModelDownloadEvent.Close)
        advanceUntilIdle()
        assertEquals(listOf(ModelDownloadEffect.OpenLicenses, ModelDownloadEffect.Close), effects)
        assertEquals(0, ports.declines)
        assertEquals(0, ports.consents)
    }

    private class FakeModelDownloadPorts : ModelDownloadPorts {
        var plan: ModelPlan = FRESH
        var usable: Long = Long.MAX_VALUE
        var askNotifications = false
        var notificationsMarked = false
        var consents = 0
        var declines = 0
        val transcribed = mutableListOf<String>()
        val downloads = mutableListOf<Set<ModelPart>>()

        override suspend fun plan(): ModelPlan = plan

        override suspend fun usableBytes(): Long = usable

        override suspend fun recordConsent() {
            consents++
        }

        override suspend fun recordDeclined() {
            declines++
        }

        override suspend fun transcribe(noteId: String): TranscribeOutcome {
            transcribed += noteId
            return TranscribeOutcome.Queued(changed = true)
        }

        override fun downloadModels(parts: Set<ModelPart>) {
            downloads += parts
        }

        override suspend fun shouldAskNotifications(): Boolean = askNotifications && !notificationsMarked

        override suspend fun markNotificationsAsked() {
            notificationsMarked = true
        }
    }

    private companion object {
        const val NOTE = "note-1"
        val FRESH = ModelPlan.of { false }
    }
}
