package io.codemyriad.cassini.data.recorder

import io.codemyriad.cassini.recorder.PartialDownload
import io.codemyriad.cassini.recorder.SyncedRecording
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class RecorderStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val file: File get() = File(File(folder.root, "recorder"), "recorder.json")
    private val device = RecorderDevice("00:11:22:33:44:55", "Cassini_55")

    @Test fun aMissingFileIsNoRecorder() = runTest {
        val store = RecorderStore(file, Dispatchers.IO)
        assertEquals(RecorderSettings(), store.settings.first())
        assertNull(store.read().current)
    }

    @Test fun everyMemberSurvivesANewProcess() = runTest {
        val state = RecorderDeviceState(
            recordings = listOf(
                StoredRecording(SyncedRecording("REC_0001.wav", 1_000, "n1", "ab", "v1:00ff", "v0:11ee")),
                StoredRecording(SyncedRecording("REC, with comma.wav", 2_000, null), failure = "IMPORT_WAV"),
                StoredRecording(SyncedRecording("REC_0003.wav", 3_000, "n3"), absent = true),
            ),
            partial = PartialDownload("REC_0002.wav", 9_000, 3_840),
            lastSync = LastSync(
                42,
                "OK",
                null,
                downloaded = 1,
                uploaded = 2,
                downloadedBytes = 9_000,
                downloadMillis = 88,
            ),
            lastSuccessAt = 40,
            rebindNeeded = true,
            transcriptsUnsupportedFirmware = "openfw_v5",
            transcriptsUnsupportedAt = 41,
        )
        val settings = RecorderSettings(
            binding = RecorderBinding(device, "wrapped-key"),
            autoSync = true,
            syncWhileRecording = true,
            devices = mapOf(device.address to state, "AA:BB:CC:DD:EE:FF" to RecorderDeviceState()),
        )
        RecorderStore(file, Dispatchers.IO).update { settings }
        val restored = RecorderStore(file, Dispatchers.IO)
        assertEquals(settings, restored.read())
        assertEquals(state, restored.read().current)
        assertEquals(state.syncState().partial, PartialDownload("REC_0002.wav", 9_000, 3_840))
    }

    @Test fun updatesOfOneRecorderKeepTheOthersAndForgetKeepsTheRecord() = runTest {
        val store = RecorderStore(file, Dispatchers.IO)
        store.update { it.copy(binding = RecorderBinding(device, "k")) }
        store.updateDevice(device.address) { it.copy(lastSuccessAt = 7) }
        store.updateDevice("other") { it.copy(rebindNeeded = true) }
        store.update { it.copy(binding = null) }
        val restored = RecorderStore(file, Dispatchers.IO).read()
        assertNull(restored.binding)
        assertEquals(7L, restored.devices.getValue(device.address).lastSuccessAt)
        assertTrue(restored.devices.getValue("other").rebindNeeded)
    }

    @Test fun anUnreadableFileStartsEmptyAndTheNextChangeReplacesIt() = runTest {
        file.parentFile!!.mkdirs()
        file.writeText("{not json")
        val store = RecorderStore(file, Dispatchers.IO)
        assertEquals(RecorderSettings(), store.read())
        // The unreadable file is kept aside.
        assertEquals("{not json", File(file.parentFile, "recorder.json.bad").readText())
        store.update { it.copy(autoSync = true) }
        assertEquals(RecorderSettings(autoSync = true), RecorderStore(file, Dispatchers.IO).read())
        file.writeText("""{"version": 99}""")
        assertEquals(RecorderSettings(), RecorderStore(file, Dispatchers.IO).read())
    }

    @Test fun aCancelledUpdateKeepsTheFileAndTheMemoryTogether() = runTest {
        val store = RecorderStore(file, Dispatchers.IO)
        store.read()
        // The update runs up to its write on IO; the cancellation comes while the write runs.
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            store.update { it.copy(autoSync = true) }
        }
        job.cancel()
        job.join()
        assertTrue(RecorderStore(file, Dispatchers.IO).read().autoSync)
        assertTrue(store.read().autoSync)
    }
}
