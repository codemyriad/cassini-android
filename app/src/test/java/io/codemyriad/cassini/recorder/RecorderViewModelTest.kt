package io.codemyriad.cassini.recorder

import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import io.codemyriad.cassini.core.library.JobState
import io.codemyriad.cassini.data.recorder.LastSync
import io.codemyriad.cassini.data.recorder.RecorderBinding
import io.codemyriad.cassini.data.recorder.RecorderDevice
import io.codemyriad.cassini.data.recorder.RecorderDeviceState
import io.codemyriad.cassini.data.recorder.RecorderSettings
import io.codemyriad.cassini.data.recorder.StoredRecording
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The Recorder screen's ViewModel over fake ports. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RecorderViewModelTest {
    private val ports = FakeScreenPorts()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun showsTheBoundRecorderAndTheStateOfEachRecording() = runTest {
        val viewModel = RecorderViewModel(ports)
        viewModel.state.test {
            assertNull(awaitItem().device)
            ports.settings.value = RecorderSettings(
                binding = RecorderBinding(DEVICE, "wrapped"),
                autoSync = true,
                devices = mapOf(
                    DEVICE.address to RecorderDeviceState(
                        recordings = listOf(
                            stored("REC_0001.wav", "a", uploaded = "v1"),
                            stored("REC_0002.wav", "b"),
                            stored("REC_0003.wav", "c"),
                            stored("REC_0004.wav", null, failure = RecorderSync.IMPORT_FAILURE + "WAV"),
                            stored("REC_0005.wav", "gone"),
                            stored("REC_0006.wav", "d", failure = RecorderSync.UPLOAD_FAILURE + "BAD_NAME"),
                            stored("REC_0007.wav", "e"),
                            stored("REC_0008.wav", "f", absent = true),
                            stored("REC_0009.wav", "g"),
                            stored("REC_0010.wav", "h", uploaded = "v1"),
                        ),
                        lastSync = LastSync(1, RecorderSync.OUTCOME_OK, downloaded = 2),
                    ),
                ),
            )
            ports.notes.value = mapOf(
                "a" to NoteSyncInfo("Standup", null, transcribed = true),
                "b" to NoteSyncInfo("Call", JobState.NEEDS_CONSENT, transcribed = false),
                "c" to NoteSyncInfo("Talk", JobState.RUNNING, transcribed = false),
                "d" to NoteSyncInfo("Long name", null, transcribed = true),
                "e" to NoteSyncInfo("Waits", null, transcribed = true),
                "f" to NoteSyncInfo("Wiped", null, transcribed = true),
                "g" to NoteSyncInfo("Silence", null, transcribed = false, noSpeech = true),
                "h" to NoteSyncInfo("Quiet", null, transcribed = false, noSpeech = true),
            )
            val state = expectMostRecentItem()
            assertEquals(DEVICE, state.device)
            assertEquals(true, state.autoSync)
            assertEquals(LastSync(1, RecorderSync.OUTCOME_OK, downloaded = 2), state.lastSync)
            assertEquals(RecorderQueueCounts(transcribing = 1, waitingForRecorder = 2), state.queue)
            assertEquals(
                listOf(
                    RecordingRow("REC_0010.wav", "h", "Quiet", RecordingStatus.UPLOADED_NO_SPEECH),
                    // A 0-word sidecar waits in the queue until uploaded to the recorder.
                    RecordingRow("REC_0009.wav", "g", "Silence", RecordingStatus.TRANSCRIBED_NO_SPEECH),
                    RecordingRow("REC_0008.wav", "f", "Wiped", RecordingStatus.TRANSCRIBED_NOT_ON_RECORDER),
                    RecordingRow("REC_0007.wav", "e", "Waits", RecordingStatus.TRANSCRIBED),
                    RecordingRow("REC_0006.wav", "d", "Long name", RecordingStatus.UPLOAD_FAILED, "BAD_NAME"),
                    RecordingRow("REC_0005.wav", null, null, RecordingStatus.NOTE_GONE),
                    RecordingRow("REC_0004.wav", null, null, RecordingStatus.IMPORT_FAILED, "WAV"),
                    RecordingRow("REC_0003.wav", "c", "Talk", RecordingStatus.TRANSCRIBING),
                    RecordingRow("REC_0002.wav", "b", "Call", RecordingStatus.WAITING_FOR_CONSENT),
                    RecordingRow("REC_0001.wav", "a", "Standup", RecordingStatus.UPLOADED),
                ),
                state.recordings,
            )
        }
    }

    @Test fun libraryBannerFormatsActiveTransfersAndWaitingQueue() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = RecorderSettings(
            binding = RecorderBinding(DEVICE, "wrapped"),
            autoSync = true,
            devices = mapOf(
                DEVICE.address to RecorderDeviceState(
                    recordings = listOf(
                        stored("REC_0001.wav", "a"),
                        stored("REC_0002.wav", "b"),
                    ),
                ),
            ),
        )
        val notes = mapOf(
            "a" to NoteSyncInfo("One", JobState.RUNNING, transcribed = false),
            "b" to NoteSyncInfo("Two", null, transcribed = true),
        )
        val dl = RecorderTexts.libraryBanner(
            context,
            settings,
            RecorderActivity.Syncing(SyncStep.DOWNLOADING, "REC_0056.wav", done = 42, total = 100),
            notes,
        )!!
        assertEquals("Downloading REC_0056.wav", dl.title)
        assertEquals(42, dl.percent)
        assertTrue((dl.progress ?: 0f) > 0.4f)
        assertTrue(dl.line.contains("1 transcribing") && dl.line.contains("1 waiting"))

        val queued = RecorderTexts.libraryBanner(context, settings, RecorderActivity.Idle, notes)!!
        assertEquals("1 transcript waiting for the recorder", queued.title)
        assertNull(queued.percent)
        assertTrue(queued.line.contains("1 transcribing"))

        assertNull(
            RecorderTexts.libraryBanner(
                context,
                settings.copy(devices = emptyMap()),
                RecorderActivity.Idle,
                emptyMap(),
            ),
        )
    }

    @Test fun bindingAsksForThePinAndSyncsWhenBound() = runTest {
        val viewModel = RecorderViewModel(ports)
        viewModel.state.test {
            awaitItem()
            viewModel.onEvent(RecorderEvent.StartBind)
            assertEquals(BindUi.Instructions, awaitItem().bind)
            viewModel.onEvent(RecorderEvent.DevicePicked(DEVICE))
            assertEquals(BindUi.Pin(wrong = false), expectMostRecentItem().bind)
            // Not six digits: ignored.
            viewModel.onEvent(RecorderEvent.PinEntered("12"))
            viewModel.onEvent(RecorderEvent.PinEntered("123456"))
            assertEquals(BindUi.Working, expectMostRecentItem().bind)
            assertEquals(listOf("123456"), ports.pins)
            ports.bindResult.complete(BindOutcome.Bound)
            assertNull(expectMostRecentItem().bind)
            assertEquals(1, ports.syncs)
        }
    }

    @Test fun aClosedPairingWindowIsShownAndCancelGoesBack() = runTest {
        val viewModel = RecorderViewModel(ports)
        viewModel.state.test {
            awaitItem()
            ports.bindResult.complete(BindOutcome.PairingClosed)
            ports.askPin = false
            viewModel.onEvent(RecorderEvent.DevicePicked(DEVICE))
            assertEquals(BindUi.Failed(BindFailure.PairingClosed), expectMostRecentItem().bind)
            viewModel.onEvent(RecorderEvent.CancelBind)
            assertNull(expectMostRecentItem().bind)
            assertEquals(0, ports.syncs)
        }
    }

    @Test fun permissionAnswersAreReadOnRefreshAndEventsReachThePorts() = runTest {
        ports.readiness = RadioReadiness.NO_PERMISSION
        val viewModel = RecorderViewModel(ports)
        viewModel.state.test {
            assertEquals(RadioReadiness.NO_PERMISSION, awaitItem().readiness)
            ports.readiness = RadioReadiness.READY
            viewModel.onEvent(RecorderEvent.Refresh)
            assertEquals(RadioReadiness.READY, awaitItem().readiness)
            viewModel.onEvent(RecorderEvent.AutoSync(true))
            assertEquals(true, awaitItem().autoSync)
            // "Sync while the recorder records" is on by default and toggles off and on.
            assertEquals(true, viewModel.state.value.syncWhileRecording)
            viewModel.onEvent(RecorderEvent.SyncWhileRecording(false))
            assertEquals(false, awaitItem().syncWhileRecording)
            viewModel.onEvent(RecorderEvent.SyncWhileRecording(true))
            assertEquals(true, awaitItem().syncWhileRecording)
            viewModel.onEvent(RecorderEvent.ListBonded)
            assertEquals(BindUi.Choose(listOf(DEVICE)), awaitItem().bind)
        }
        viewModel.effects.test {
            viewModel.onEvent(RecorderEvent.OpenNote("a"))
            assertEquals(RecorderEffect.OpenNote("a"), awaitItem())
        }
    }

    private fun stored(
        name: String,
        noteId: String?,
        uploaded: String? = null,
        failure: String? = null,
        absent: Boolean = false,
    ) = StoredRecording(SyncedRecording(name, 1_000, noteId, transcriptUploadedVersion = uploaded), failure, absent)

    private class FakeScreenPorts : RecorderScreenPorts {
        override val settings = MutableStateFlow(RecorderSettings())
        override val activity: StateFlow<RecorderActivity> = MutableStateFlow(RecorderActivity.Idle)
        override val notes = MutableStateFlow<Map<String, NoteSyncInfo>>(emptyMap())
        override val companionSupported = true
        var readiness = RadioReadiness.READY
        var syncs = 0
        var askPin = true
        val pins = mutableListOf<String>()
        val bindResult = CompletableDeferred<BindOutcome>()

        override fun readiness(): RadioReadiness = readiness

        override fun bondedRecorders(): List<RecorderDevice> = listOf(DEVICE)

        override fun syncNow() {
            syncs++
        }

        override suspend fun setAutoSync(enabled: Boolean) = settings.update { it.copy(autoSync = enabled) }

        override suspend fun setSyncWhileRecording(enabled: Boolean) =
            settings.update { it.copy(syncWhileRecording = enabled) }

        override suspend fun bind(device: RecorderDevice, pin: suspend (Boolean) -> String?): BindOutcome {
            if (askPin) pin(false)?.let(pins::add)
            return bindResult.await()
        }

        override suspend fun forget(device: RecorderDevice) = settings.update { it.copy(binding = null) }
    }
}
