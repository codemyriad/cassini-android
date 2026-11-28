package io.codemyriad.cassini.recorder

import io.codemyriad.cassini.core.recorder.CwxSpeaker
import io.codemyriad.cassini.core.recorder.CwxTranscript
import io.codemyriad.cassini.core.recorder.CwxWord
import io.codemyriad.cassini.core.recorder.FakeRecorder
import io.codemyriad.cassini.core.recorder.XrecAuth
import io.codemyriad.cassini.core.recorder.XrecFailure
import io.codemyriad.cassini.data.recorder.RecorderStore
import io.codemyriad.cassini.data.recorder.StoredRecording
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
import java.time.LocalDateTime
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
        var allowPrecompute = true
        var deferAutoTranscript = false

        override suspend fun openLivePrecompute(partFile: File, displayName: String): LivePrecomputeSession? {
            if (!allowPrecompute) {
                events += "openLiveSkipped:$displayName"
                return null
            }
            events += "openLive:$displayName"
            return object : LivePrecomputeSession {
                override fun onBytesAvailable(bytes: Long) {
                    precomputeBytes += bytes
                    events += "bytes:$bytes"
                }

                override suspend fun awaitStopped() {
                    events += "awaitStopped"
                }

                override suspend fun finishAndInstall(noteId: String) {
                    events += "finishAndInstall:$noteId"
                }

                override fun close() {
                    events += "closeLive"
                }
            }
        }

        override suspend fun installLivePrecompute(partFile: File, noteId: String) {
            events += "installLivePrecompute:$noteId"
        }

        override fun discardLivePrecompute(partFile: File) {
            events += "discardLivePrecompute:${partFile.name}"
        }

        override suspend fun import(file: File, displayName: String): LibraryImport {
            events += "import:$displayName"
            return backing.import(file, displayName)
        }

        override suspend fun setRecordingStartIfAbsent(noteId: String, startedAt: String) =
            backing.setRecordingStartIfAbsent(noteId, startedAt)

        override suspend fun transcribe(noteId: String) {
            events += "transcribe:$noteId"
            backing.transcribe(noteId)
            if (!deferAutoTranscript) {
                backing.ready[noteId] = RecorderTranscript("v1", sampleCwx)
                backing.activeCount = 0
            }
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
        val stoppedIdx = library.events.indexOf("awaitStopped")
        val importIdx = library.events.indexOf("import:REC_0001.wav")
        val installIdx = library.events.indexOf("finishAndInstall:note-1")
        val transcribeIdx = library.events.indexOf("transcribe:note-1")
        assertTrue(stoppedIdx >= 0 && importIdx > stoppedIdx && installIdx > importIdx && transcribeIdx > installIdx)

        assertTrue("+XREC:SYNC,1" in recorder.commands)
        assertEquals(0, recorder.lastSyncPending)
        assertTrue(recorder.files.containsKey("REC_0001.cwx"))
    }

    @Test fun stopDuringBacklogSwitchesToIdleTransferAndFeedsInferenceBeforeImport() = runBlocking {
        val library = LiveRecordingLibrary()
        val wav = liveWav(600_000, headerTag = 0x11)
        recorder.files["REC_0001.wav"] = wav
        recorder.recording = true
        recorder.activeFlushedBytes = wav.size
        recorder.beforeGets = {
            recorder.recording = false
            recorder.beforeGets = null
        }
        val (sync, store) = recorderSync(folder.root, radio, library)
        store.bound(key)

        val outcome = sync.sync() as SyncOutcome.Done
        assertEquals(1, outcome.downloaded)
        val commands = recorder.commands.dropWhile { !it.startsWith("+XREC:GETS,") }
        val liveRequests = commands.takeWhile { it != "+XREC:STAT" }.count { it.startsWith("+XREC:GETS,") }
        assertTrue("GETS before noticing Stop: $liveRequests", liveRequests in 1..4)
        assertTrue(library.precomputeBytes.any { it in 262_144L until wav.size.toLong() })
        assertArrayEquals(wav, library.backing.imported.getValue("note-1"))
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

    @Test fun timedListingStampsLiveAndNormalDownloadsAndBackfillsPreviouslySyncedRecordings() = runBlocking {
        recorder.listTimestampsSupported = true
        recorder.defaultFileTime = LocalDateTime.of(2026, 11, 1, 0, 0, 0)
        recorder.files["REC_0001.wav"] = recording(1_000, seed = 1)
        recorder.fileTimes["REC_0001.wav"] = LocalDateTime.of(2026, 11, 18, 9, 10, 12)
        recorder.files["REC_0002.wav"] = recording(1_000, seed = 2)
        recorder.fileTimes["REC_0002.wav"] = LocalDateTime.of(2026, 11, 18, 11, 0, 0)
        recorder.files["REC_0003.wav"] = recording(2_000, seed = 3)
        recorder.fileTimes["REC_0003.wav"] = LocalDateTime.of(2026, 11, 19, 14, 15, 16)
        recorder.files["REC_0004.wav"] = liveWav(8_192, headerTag = 0x44)
        recorder.fileTimes["REC_0004.wav"] = null
        recorder.recording = true
        recorder.activeFlushedBytes = 4_096

        val library = LiveRecordingLibrary()
        library.backing.startedAtByNote["note-dated"] = "2026-11-20T08:00:00+00:00"
        val store = RecorderStore(File(folder.root, "recorder.json"), Dispatchers.IO)
        store.bound(key)
        store.updateDevice(DEVICE.address) {
            it.copy(
                recordings = listOf(
                    StoredRecording(SyncedRecording("REC_0001.wav", 1_000, "note-old")),
                    StoredRecording(SyncedRecording("REC_0002.wav", 1_000, "note-dated")),
                ),
            )
        }
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
                    recorder.activeFlushedBytes = null
                    recorder.recording = false
                },
            ),
        )

        val outcome = sync.sync() as SyncOutcome.Done
        assertEquals(2, outcome.downloaded)
        assertTrue("+XREC:LS,T" in recorder.commands)
        assertEquals("2026-11-18T09:10:12+00:00", library.backing.startedAtByNote["note-old"])
        assertEquals("2026-11-20T08:00:00+00:00", library.backing.startedAtByNote["note-dated"])

        val note3 = library.backing.titles.entries.first { it.value == "REC_0003.wav" }.key
        val note4 = library.backing.titles.entries.first { it.value == "REC_0004.wav" }.key
        assertEquals("2026-11-19T14:15:16+00:00", library.backing.startedAtByNote[note3])
        // FakeRecorder reports stat recordingSeconds=12 against NOW=2026-11-25T10:11:12Z.
        assertEquals("2026-11-25T10:11:00+00:00", library.backing.startedAtByNote[note4])
    }

    @Test fun partialRangeBeforeBusyReconcilesOffsetWithoutDuplicatingBytes() = runBlocking {
        val library = LiveRecordingLibrary()
        val liveBody = liveWav(8_192, headerTag = 0x11)
        val finalizedWav = liveBody.copyOf().apply { for (i in 12 until 44) this[i] = 0x77.toByte() }
        recorder.recording = true
        recorder.files["REC_0001.wav"] = liveBody
        recorder.activeFlushedBytes = 4_096
        recorder.busyAtFrame = 1_280L

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
                    recorder.files["REC_0001.wav"] = finalizedWav
                    recorder.activeFlushedBytes = null
                    recorder.recording = false
                },
            ),
        )

        val outcome = sync.sync() as SyncOutcome.Done
        assertEquals(1, outcome.downloaded)
        assertTrue(recorder.commands.any { it.startsWith("+XREC:GETS,1280,") })
        assertArrayEquals(finalizedWav, library.backing.imported.getValue("note-1"))
    }

    @Test fun disconnectAndStopAtSameFlushedLengthRefreshesHeaderOnReconnect() = checkReconnect(8_192)

    @Test fun growingRecordingReusesDownloadedPcmAfterDisconnectAndStop() = checkReconnect(16_384)

    @Test fun reusedRecordingNumberWithSameOpeningRestartsFromZero() = checkReconnect(16_384, replaced = true)

    private fun checkReconnect(finalSize: Int, replaced: Boolean = false) = runBlocking {
        val library = LiveRecordingLibrary()
        val liveBody = liveWav(8_192, headerTag = 0x11)
        val finalizedWav = liveWav(finalSize, headerTag = 0x77).apply {
            if (replaced) fill(0x44, 4_096, size)
        }
        recorder.recording = true
        recorder.files["REC_0001.wav"] = liveBody
        recorder.activeFlushedBytes = 8_192

        val store = RecorderStore(File(folder.root, "recorder.json"), Dispatchers.IO)
        store.bound(key)
        var dropOnPoll = true
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
                    if (dropOnPoll) {
                        dropOnPoll = false
                        recorder.close()
                    }
                },
            ),
        )

        val firstOutcome = sync.sync() as SyncOutcome.Failed
        assertEquals(XrecFailure.DISCONNECTED, firstOutcome.failure)
        assertEquals(
            PartialDownload("REC_0001.wav", 8_192, 8_192, liveHeaderPending = true),
            store.read().current!!.partial,
        )

        recorder.recording = false
        recorder.activeFlushedBytes = null
        recorder.files["REC_0001.wav"] = finalizedWav

        val secondOutcome = sync.sync() as SyncOutcome.Done
        assertEquals(1, secondOutcome.downloaded)
        val requests = radio.device.commands
        if (replaced) {
            assertTrue("+XREC:GETS,0,$finalSize,640,REC_0001.wav" in requests)
        } else {
            assertTrue("+XREC:GETS,0,44,640,REC_0001.wav" in requests)
            assertTrue(requests.none { it.startsWith("+XREC:GETS,0,$finalSize,") })
            if (finalSize > liveBody.size) assertTrue(requests.any { it.startsWith("+XREC:GETS,8192,") })
        }
        assertArrayEquals(finalizedWav, library.backing.imported.getValue("note-1"))

        val importIdx = library.events.indexOf("import:REC_0001.wav")
        val installIdx = library.events.indexOf("installLivePrecompute:note-1")
        val transcribeIdx = library.events.indexOf("transcribe:note-1")
        assertTrue(importIdx >= 0 && installIdx > importIdx && transcribeIdx > installIdx)
        assertTrue("discardLivePrecompute:REC_0001.wav.part" in library.events)
    }

    @Test fun transientPrecomputeUnavailabilityRetriesAndAttachesMidStream() = runBlocking {
        val library = LiveRecordingLibrary().apply { allowPrecompute = false }
        val liveBody = liveWav(320 * 1024, headerTag = 0x15)
        val finalizedWav = liveBody.copyOf().apply { for (i in 12 until 44) this[i] = 0x77.toByte() }
        recorder.recording = true
        recorder.files["REC_0001.wav"] = liveBody
        recorder.activeFlushedBytes = 64 * 1024

        var now = NOW
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
                clock = { now },
                zone = { ZoneOffset.UTC },
                timeouts = TEST_TIMEOUTS,
                livePollMillis = 0L,
                sidecarPollMillis = 0L,
                wait = {
                    when (polls++) {
                        0 -> {
                            now += 5_500L
                            library.allowPrecompute = true
                            recorder.activeFlushedBytes = 320 * 1024
                        }
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
        assertTrue("openLiveSkipped:REC_0001.wav" in library.events)
        assertTrue("openLive:REC_0001.wav" in library.events)
        assertTrue("finishAndInstall:note-1" in library.events)
        assertEquals(327_680L, library.precomputeBytes.last())
    }

    private fun verifyMidSyncRecordingStart(installHook: (ByteArray) -> Unit) = runBlocking {
        val library = LiveRecordingLibrary()
        val olderWav = liveWav(8_192, headerTag = 0x11, seed = 1)
        val liveBody = liveWav(16_000, headerTag = 0x22, seed = 9)
        val finalizedWav = liveBody.copyOf().apply { for (i in 12 until 44) this[i] = 0x77.toByte() }
        recorder.files["REC_0001.wav"] = olderWav
        val store = RecorderStore(File(folder.root, "recorder.json"), Dispatchers.IO)
        store.bound(key)
        var polls = 0
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
                    val dev = radio.device
                    when (polls++) {
                        0 -> dev.activeFlushedBytes = 12_000
                        else -> {
                            dev.files["REC_0002.wav"] = finalizedWav
                            dev.activeFlushedBytes = null
                            dev.recording = false
                        }
                    }
                },
            ),
        )
        library.deferAutoTranscript = true
        assertEquals(1, (sync.sync() as SyncOutcome.Done).downloaded)
        assertFalse(radio.device.files.containsKey("REC_0001.cwx"))
        library.deferAutoTranscript = false
        library.backing.ready["note-1"] = RecorderTranscript("v1", LiveRecordingLibrary.sampleCwx)
        installHook(liveBody)

        val outcome = sync.sync() as SyncOutcome.Done
        assertEquals(1, outcome.downloaded)
        assertEquals(2, outcome.uploaded)
        assertTrue(radio.device.files.containsKey("REC_0001.cwx"))
        assertTrue(radio.device.files.containsKey("REC_0002.cwx"))
    }

    @Test fun liveRecordingStartingDuringSuspectCheckTailsAndUploadsOnSameSession() =
        verifyMidSyncRecordingStart { liveBody ->
            radio.device.beforeGets = { name ->
                val dev = radio.device
                if (name == "REC_0001.wav" && !dev.recording) {
                    dev.beforeGets = null
                    dev.files["REC_0002.wav"] = liveBody
                    dev.activeFlushedBytes = 4_096
                    dev.recording = true
                }
            }
        }

    @Test fun liveRecordingStartingDuringUploadPreemptsAndTailsOnSameSession() =
        verifyMidSyncRecordingStart { liveBody ->
            radio.device.beforePut = {
                val dev = radio.device
                if (!dev.recording) {
                    dev.beforePut = null
                    dev.files["REC_0002.wav"] = liveBody
                    dev.activeFlushedBytes = 4_096
                    dev.recording = true
                }
            }
        }
}
