package io.codemyriad.cassini.recorder

import io.codemyriad.cassini.core.recorder.CwxSpeaker
import io.codemyriad.cassini.core.recorder.CwxTranscript
import io.codemyriad.cassini.core.recorder.CwxWord
import io.codemyriad.cassini.core.recorder.FakeRecorder
import io.codemyriad.cassini.core.recorder.XrecAuth
import io.codemyriad.cassini.core.recorder.XrecFailure
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for `+XREC:SYNC,<pending>` accounting across re-transcriptions, transient upload failures, and active
 * recording skips.
 */
@RunWith(RobolectricTestRunner::class)
class RecorderSyncStatusReportTest {
    @get:Rule val folder = TemporaryFolder()

    private val key = ByteArray(XrecAuth.KEY_BYTES) { it.toByte() }
    private val recorder = FakeRecorder(key = key.copyOf(), syncStatusSupported = true)
    private val radio = FakeRadio(recorder)
    private val library = FakeLibrary()

    @After fun closeLink() = radio.device.close()

    private fun sync() = recorderSync(folder.root, radio, library)

    private fun assertDone(expected: SyncOutcome.Done, actual: SyncOutcome) =
        assertEquals(expected, (actual as? SyncOutcome.Done)?.copy(speed = null) ?: actual)

    private fun sampleTranscript(title: String) = CwxTranscript(
        title,
        2_000,
        listOf(CwxSpeaker("Ada", 0x679CFF)),
        listOf(CwxWord(0, 400, 0, "Hello")),
    )

    @Test fun pendingSyncReportIncludesRetranscribingNoteWhoseFirstSidecarAlreadyUploaded() = runBlocking {
        recorder.files["REC_0001.wav"] = recording(3_000, seed = 1)
        val (sync, store) = sync()
        store.bound(key)
        assertDone(SyncOutcome.Done(1, 0, 0, false), sync.sync())
        library.ready["note-1"] = RecorderTranscript("v1", sampleTranscript("One"))
        assertDone(SyncOutcome.Done(0, 1, 0, false), sync.sync())
        assertEquals(0, radio.device.lastSyncPending)

        library.activeCount = 1
        assertEquals(1, sync.activeTranscriptions())
        assertDone(SyncOutcome.Done(0, 0, 0, false), sync.sync())
        assertEquals(1, radio.device.lastSyncPending)
    }

    @Test fun pendingSyncReportIncludesTransientUploadFailureWhileRetranscribing() = runBlocking {
        recorder.files["REC_0001.wav"] = recording(3_000, seed = 1)
        val (sync, store) = sync()
        store.bound(key)
        assertDone(SyncOutcome.Done(1, 0, 0, false), sync.sync())
        store.updateDevice(DEVICE.address) { state ->
            state.copy(
                recordings = state.recordings.map {
                    it.copy(failure = RecorderSync.UPLOAD_FAILURE + XrecFailure.CARD_IO.name)
                },
            )
        }
        library.activeCount = 1

        assertEquals(1, sync.activeTranscriptions())
        assertDone(SyncOutcome.Done(0, 0, 0, false), sync.sync())
        assertEquals(1, radio.device.lastSyncPending)
    }

    @Test fun pendingSyncReportIncludesFilesWaitingForActiveRecordingInBothModes() = runBlocking {
        recorder.syncsWhileRecording = true
        recorder.recording = true
        recorder.files["REC_0001.wav"] = recording(4_000, seed = 1)
        val (sync, store) = sync()
        store.bound(key)
        store.update { it.copy(syncWhileRecording = true) }

        assertDone(SyncOutcome.Done(0, 0, 1, deviceRecording = true), sync.sync(SyncMode.UPLOADS))
        assertEquals(1, radio.device.lastSyncPending)

        assertDone(SyncOutcome.Done(0, 0, 1, deviceRecording = true, recordingPoll = true), sync.sync(SyncMode.FULL))
        assertEquals(1, radio.device.lastSyncPending)
    }
}
