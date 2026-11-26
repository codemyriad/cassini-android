package io.codemyriad.cassini.recorder

import io.codemyriad.cassini.core.recorder.CwxSpeaker
import io.codemyriad.cassini.core.recorder.CwxTranscript
import io.codemyriad.cassini.core.recorder.CwxWord
import io.codemyriad.cassini.core.recorder.FakeRecorder
import io.codemyriad.cassini.core.recorder.XrecAuth
import io.codemyriad.cassini.data.recorder.RecorderStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.security.MessageDigest
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class RecorderLiveStreamingTest {
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

    private class LiveRecordingLibrary : RecorderLibrary {
        val backing = FakeLibrary()
        val events = mutableListOf<String>()
        val precomputeBytes = mutableListOf<Long>()

        override suspend fun openLivePrecompute(partFile: File, displayName: String): LivePrecomputeSession {
            events += "openLive:$displayName"
            return object : LivePrecomputeSession {
                override fun onBytesAvailable(bytes: Long) {
                    precomputeBytes += bytes
                    events += "bytes:$bytes"
                }

                override suspend fun finishAndInstall(noteId: String) {
                    events += "finishAndInstall:$noteId"
                }

                override fun close() {
                    events += "closeLive"
                }
            }
        }

        override suspend fun import(file: File, displayName: String): LibraryImport {
            events += "import:$displayName"
            return backing.import(file, displayName)
        }

        override suspend fun transcribe(noteId: String) {
            events += "transcribe:$noteId"
            backing.transcribe(noteId)
            backing.ready[noteId] = RecorderTranscript("v1", sampleCwx)
            backing.activeCount = 0
        }

        override suspend fun needsTranscription(noteId: String): Boolean = backing.needsTranscription(noteId)

        override suspend fun transcripts(noteIds: Collection<String>): Map<String, RecorderTranscript> =
            backing.transcripts(noteIds)

        override suspend fun activeTranscriptions(noteIds: Collection<String>): Int =
            backing.activeTranscriptions(noteIds)

        companion object {
            val sampleCwx = CwxTranscript(
                "REC_0001.wav",
                4_000,
                listOf(CwxSpeaker("Ada", 0x679CFF)),
                listOf(CwxWord(0, 500, 0, "Live")),
            )
        }
    }

    @Test fun liveStreamingPullsFlushedChunksRefreshesHeaderPrecomputesAndUploadsSidecarOnSameSession() = runBlocking {
        val library = LiveRecordingLibrary()
        val liveBody = liveWav(16_000, headerTag = 0x11)
        val finalizedWav = liveBody.copyOf()
        for (i in 12 until 44) finalizedWav[i] = 0x77.toByte()

        recorder.recording = true
        recorder.files["REC_0001.wav"] = liveBody
        recorder.activeFlushedBytes = 44

        var waitCalls = 0
        val store = RecorderStore(File(folder.root, "recorder.json"), Dispatchers.IO)
        store.bound(key)
        val sync = RecorderSync(
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
                wait = {
                    when (waitCalls++) {
                        0 -> recorder.activeFlushedBytes = 4_096
                        1 -> recorder.activeFlushedBytes = 10_000
                        else -> {
                            recorder.files["REC_0001.wav"] = finalizedWav
                            recorder.activeFlushedBytes = null
                            recorder.recording = false
                        }
                    }
                },
            ),
        )

        val outcome = sync.sync() as SyncOutcome.Done
        assertEquals(1, outcome.downloaded)
        assertEquals(1, outcome.uploaded)
        assertEquals(0, outcome.skipped)
        assertFalse(outcome.deviceRecording)
        assertEquals(1, radio.opens)

        val importedBytes = library.backing.imported.getValue("note-1")
        assertArrayEquals(finalizedWav, importedBytes)
        val expectedHead = XrecAuth.toHex(MessageDigest.getInstance("SHA-256").digest(finalizedWav.copyOf(4096)))
        assertEquals(expectedHead, store.read().current!!.recordings.single().recording.headSha256)

        assertEquals(listOf(4_096L, 10_000L), library.precomputeBytes)
        val importIdx = library.events.indexOf("import:REC_0001.wav")
        val installIdx = library.events.indexOf("finishAndInstall:note-1")
        val transcribeIdx = library.events.indexOf("transcribe:note-1")
        assertTrue(importIdx >= 0 && installIdx > importIdx && transcribeIdx > installIdx)

        assertTrue("+XREC:SYNC,1" in recorder.commands)
        assertEquals(0, recorder.lastSyncPending)
        assertTrue(recorder.files.containsKey("REC_0001.cwx"))
    }

    @Test fun stereoLiveStreamingSucceedsInsteadOfDeferringWithLater() = runBlocking {
        val library = FakeLibrary()
        val wav = liveWav(8_192, headerTag = 0x22)
        recorder.stereo = true
        recorder.recording = true
        recorder.files["REC_0001.wav"] = wav
        recorder.activeFlushedBytes = 2_048

        var polls = 0
        val store = RecorderStore(File(folder.root, "recorder.json"), Dispatchers.IO)
        store.bound(key)
        val sync = RecorderSync(
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
                wait = {
                    if (polls++ == 0) {
                        recorder.activeFlushedBytes = 6_144
                    } else {
                        recorder.activeFlushedBytes = null
                        recorder.recording = false
                    }
                },
            ),
        )

        val outcome = sync.sync() as SyncOutcome.Done
        assertEquals(1, outcome.downloaded)
        assertEquals(0, recorder.laterReplies)
        assertArrayEquals(wav, library.imported.getValue("note-1"))
    }

    @Test fun legacyFirmwareWithoutLiveFlagKeepsWaitingBehavior() = runBlocking {
        recorder.liveRecordingSupported = false
        recorder.recording = true
        recorder.files["REC_0001.wav"] = liveWav(4_096, headerTag = 0x33)
        val library = FakeLibrary()
        val (sync, store) = recorderSync(folder.root, radio, library)
        store.bound(key)

        val outcome = sync.sync() as SyncOutcome.Done
        val expected = SyncOutcome.Done(0, 0, 1, deviceRecording = true, recordingPoll = true)
        assertEquals(expected, outcome.copy(speed = null))
        assertTrue(library.imported.isEmpty())
    }
}
