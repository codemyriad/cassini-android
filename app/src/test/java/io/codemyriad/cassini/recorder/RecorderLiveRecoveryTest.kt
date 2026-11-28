package io.codemyriad.cassini.recorder

import io.codemyriad.cassini.core.recorder.CwxSpeaker
import io.codemyriad.cassini.core.recorder.CwxTranscript
import io.codemyriad.cassini.core.recorder.CwxWord
import io.codemyriad.cassini.core.recorder.FakeRecorder
import io.codemyriad.cassini.core.recorder.XrecAuth
import io.codemyriad.cassini.core.recorder.XrecFailure
import io.codemyriad.cassini.data.recorder.RecorderStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class RecorderLiveRecoveryTest {
    @get:Rule val folder = TemporaryFolder()

    private val key = ByteArray(XrecAuth.KEY_BYTES) { it.toByte() }
    private val recorder = FakeRecorder(
        key = key.copyOf(),
        syncsWhileRecording = true,
        syncStatusSupported = true,
        liveRecordingSupported = true,
    )
    private val radio = FakeRadio(recorder)

    @After fun closeLink() = radio.device.close()

    private fun liveWav(totalSize: Int, headerTag: Byte, seed: Int = 5): ByteArray {
        val bytes = ByteArray(totalSize) { ((it * 37 + seed) and 0xFF).toByte() }
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(bytes, 0)
        "WAVE".toByteArray(Charsets.US_ASCII).copyInto(bytes, 8)
        for (i in 12 until 44) {
            if (i !in 8..11) bytes[i] = headerTag
        }
        return bytes
    }

    private fun sampleCwx(title: String) = CwxTranscript(
        title,
        2_000,
        listOf(CwxSpeaker("Ada", 0x679CFF)),
        listOf(CwxWord(0, 500, 0, "Live")),
    )

    private fun liveSync(
        library: RecorderLibrary,
        store: RecorderStore,
        wait: suspend (Long) -> Unit = {},
    ) = RecorderSync(
        RecorderSync.Parts(
            store = store,
            radio = radio,
            vault = FakeVault(),
            library = library,
            downloads = File(folder.root, "downloads"),
            io = Dispatchers.IO,
            clock = { NOW },
            zone = { ZoneOffset.UTC },
            timeouts = TEST_TIMEOUTS,
            livePollMillis = 0L,
            sidecarPollMillis = 0L,
            maxSidecarWaitMillis = 5_000L,
            wait = wait,
        ),
    )

    @Test fun shortRecordingStoppingDuringLocateIsStillListedImportedAndSynced() = runBlocking {
        val library = FakeLibrary()
        val olderWav = liveWav(8_192, headerTag = 0x11, seed = 1)
        val shortWav = liveWav(6_144, headerTag = 0x55, seed = 7)
        recorder.files["REC_0001.wav"] = olderWav
        val store = RecorderStore(File(folder.root, "recorder.json"), Dispatchers.IO).also { it.bound(key) }
        val sync = liveSync(library, store)
        assertEquals(1, (sync.sync() as SyncOutcome.Done).downloaded)

        var statHits = 0
        recorder.beforeGets = { name ->
            val dev = radio.device
            if (name == "REC_0001.wav" && !dev.recording) {
                dev.beforeGets = null
                dev.files["REC_0002.wav"] = shortWav
                dev.activeFlushedBytes = 44
                dev.recording = true
                dev.beforeStat = {
                    if (++statHits == 2) {
                        dev.beforeStat = null
                        dev.recording = false
                        dev.activeFlushedBytes = null
                    }
                }
            }
        }
        library.ready["note-1"] = RecorderTranscript("v1", sampleCwx("Old"))
        val outcome = sync.sync() as SyncOutcome.Done
        assertEquals(1, outcome.downloaded)
        assertEquals(1, outcome.uploaded)
        assertEquals(0, outcome.skipped)
        assertFalse(outcome.deviceRecording)
        assertArrayEquals(shortWav, library.imported.getValue("note-2"))
    }

    @Test fun backToBackRecordingStartingDuringTranscriptWaitIsTailedOnSameSession() = runBlocking {
        val library = FakeLibrary()
        val firstFinal = liveWav(8_192, headerTag = 0x77, seed = 1)
        val secondLive = liveWav(8_192, headerTag = 0x22, seed = 2)
        val secondFinal = secondLive.copyOf().apply { for (i in 12 until 44) this[i] = 0x88.toByte() }
        recorder.recording = true
        recorder.files["REC_0001.wav"] = liveWav(8_192, headerTag = 0x11, seed = 1)
        recorder.activeFlushedBytes = 4_096

        val store = RecorderStore(File(folder.root, "recorder.json"), Dispatchers.IO).also { it.bound(key) }
        var polls = 0
        val sync = liveSync(library, store) {
            val dev = radio.device
            when (polls++) {
                0 -> {
                    dev.files["REC_0001.wav"] = firstFinal
                    dev.activeFlushedBytes = null
                    dev.recording = false
                    library.activeCount = 1
                }
                5 -> {
                    dev.files["REC_0002.wav"] = secondLive
                    dev.activeFlushedBytes = 4_096
                    dev.recording = true
                }
                6 -> {
                    dev.files["REC_0002.wav"] = secondFinal
                    dev.activeFlushedBytes = null
                    dev.recording = false
                    library.ready["note-1"] = RecorderTranscript("v1", sampleCwx("One"))
                    library.ready["note-2"] = RecorderTranscript("v1", sampleCwx("Two"))
                    library.activeCount = 0
                }
            }
        }

        val outcome = sync.sync() as SyncOutcome.Done
        assertEquals(2, outcome.downloaded)
        assertEquals(2, outcome.uploaded)
        assertEquals(1, radio.opens)
        assertTrue(recorder.files.containsKey("REC_0001.cwx"))
        assertTrue(recorder.files.containsKey("REC_0002.cwx"))
        assertEquals(0, recorder.lastSyncPending)
    }

    @Test fun temporaryImportFailureDuringLiveFinalizeCountsAsFailedAndPreservesPartial() = runBlocking {
        val library = FakeLibrary().apply { reject = "SPACE" }
        recorder.recording = true
        recorder.files["REC_0001.wav"] = liveWav(8_192, headerTag = 0x11)
        recorder.activeFlushedBytes = 4_096
        val store = RecorderStore(File(folder.root, "recorder.json"), Dispatchers.IO).also { it.bound(key) }
        val sync = liveSync(library, store) {
            recorder.activeFlushedBytes = null
            recorder.recording = false
        }

        val first = sync.sync() as SyncOutcome.Done
        assertEquals(0, first.downloaded)
        assertEquals(1, first.failed)
        assertEquals(1, recorder.lastSyncPending)
        assertEquals(PartialDownload("REC_0001.wav", 8_192, 8_192), store.read().current!!.partial)

        library.reject = null
        val second = sync.sync() as SyncOutcome.Done
        assertEquals(1, second.downloaded)
        assertEquals(0, second.failed)
        assertEquals(0, radio.device.lastSyncPending)
        assertNull(store.read().current!!.partial)
    }

    @Test fun postStopReconnectOnLivePartAwaitsAsyncTranscriptAndUploadsInSameSession() = runBlocking {
        val library = FakeLibrary()
        recorder.recording = true
        recorder.files["REC_0001.wav"] = liveWav(8_192, headerTag = 0x11)
        recorder.activeFlushedBytes = 8_192
        val store = RecorderStore(File(folder.root, "recorder.json"), Dispatchers.IO).also { it.bound(key) }
        var dropFirst = true
        var waitPolls = 0
        val sync = liveSync(library, store) {
            if (dropFirst) {
                dropFirst = false
                recorder.close()
            } else if (++waitPolls == 2) {
                library.ready["note-1"] = RecorderTranscript("v1", sampleCwx("Resumed"))
                library.activeCount = 0
            }
        }

        val first = sync.sync() as SyncOutcome.Failed
        assertEquals(XrecFailure.DISCONNECTED, first.failure)
        recorder.recording = false
        recorder.activeFlushedBytes = null
        recorder.files["REC_0001.wav"] = liveWav(12_288, headerTag = 0x77)
        library.activeCount = 1

        val second = sync.sync() as SyncOutcome.Done
        assertEquals(1, second.downloaded)
        assertEquals(1, second.uploaded)
        assertEquals(0, second.failed)
        assertEquals(2, radio.opens)
        assertTrue(radio.device.files.containsKey("REC_0001.cwx"))
        assertEquals(0, radio.device.lastSyncPending)
    }

    @Test fun deletedLiveFileDuringVerifyClearsStalePartialInsteadOfFailing() = runBlocking {
        val library = FakeLibrary()
        recorder.recording = true
        recorder.files["REC_0001.wav"] = liveWav(8_192, headerTag = 0x11)
        recorder.activeFlushedBytes = 8_192
        val store = RecorderStore(File(folder.root, "recorder.json"), Dispatchers.IO).also { it.bound(key) }
        var dropFirst = true
        val sync = liveSync(library, store) {
            if (dropFirst) {
                dropFirst = false
                recorder.close()
            }
        }

        assertTrue(sync.sync() is SyncOutcome.Failed)
        recorder.recording = false
        recorder.activeFlushedBytes = null
        recorder.beforeGets = { name -> if (name == "REC_0001.wav") radio.device.files.remove(name) }

        val second = sync.sync() as SyncOutcome.Done
        assertEquals(0, second.downloaded)
        assertEquals(0, second.failed)
        assertNull(store.read().current!!.partial)
    }

    @Test fun newestRecordingSidecarUploadsAheadOfOlderPendingBacklog() = runBlocking {
        val library = FakeLibrary()
        recorder.files["REC_0001.wav"] = recording(3_000, seed = 1)
        recorder.files["REC_0002.wav"] = recording(3_000, seed = 2)
        val store = RecorderStore(File(folder.root, "recorder.json"), Dispatchers.IO).also { it.bound(key) }
        val sync = liveSync(library, store)
        assertEquals(2, (sync.sync() as SyncOutcome.Done).downloaded)

        library.ready["note-1"] = RecorderTranscript("v1", sampleCwx("Older"))
        library.ready["note-2"] = RecorderTranscript("v1", sampleCwx("Newer"))
        assertEquals(2, (sync.sync() as SyncOutcome.Done).uploaded)

        val putTargets = radio.device.commands
            .filter { it.startsWith("+XREC:PUT,0,") }
            .map { it.substringAfter("REC,").removeSuffix(":") }
        assertEquals(listOf("REC_0002.cwx", "REC_0001.cwx"), putTargets)
    }
}
