package io.codemyriad.cassini.recorder

import io.codemyriad.cassini.core.recorder.CwxReader
import io.codemyriad.cassini.core.recorder.CwxSpeaker
import io.codemyriad.cassini.core.recorder.CwxTranscript
import io.codemyriad.cassini.core.recorder.CwxWord
import io.codemyriad.cassini.core.recorder.FakeRecorder
import io.codemyriad.cassini.core.recorder.XrecAuth
import io.codemyriad.cassini.core.recorder.XrecFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The recorder sync over [FakeRecorder], the recorder's XREC state machine behind pipes, with a fake library
 * (docs/recorder-sync.md). Each test runs real blocking I/O, so it uses [runBlocking].
 */
@RunWith(RobolectricTestRunner::class)
class RecorderSyncTest {
    @get:Rule val folder = TemporaryFolder()

    private val key = ByteArray(XrecAuth.KEY_BYTES) { it.toByte() }
    private val recorder = FakeRecorder(key = key.copyOf())
    private val radio = FakeRadio(recorder)
    private val library = FakeLibrary()
    private val root: File get() = folder.root

    @After fun closeLink() = radio.device.close()

    private fun sync(clock: () -> Long = { NOW }, stallMillis: Long = RecorderSync.DEFAULT_STALL_MS) =
        recorderSync(root, radio, library, clock, stallMillis)

    private fun transcript(title: String, first: String = "Ada") = CwxTranscript(
        title,
        2_000,
        listOf(CwxSpeaker(first, 0x679CFF), CwxSpeaker("Speaker 2", 0xB0B4BC)),
        listOf(CwxWord(0, 400, 0, "Hello"), CwxWord(500, 900, 1, "there")),
    )

    private val parts: List<File> get() = File(root, "downloads").walk().filter { it.isFile }.toList()

    /** The counts of a [SyncOutcome.Done]; its measured [SyncOutcome.Done.speed] varies from run to run. */
    private fun assertDone(expected: SyncOutcome.Done, actual: SyncOutcome) =
        assertEquals(expected, (actual as? SyncOutcome.Done)?.copy(speed = null) ?: actual)

    @Test fun aSyncMeasuresItsDownloadsAndAsksForLargeWindows() = runBlocking {
        val audio = recording(150_000, seed = 5)
        recorder.files["REC_0001.wav"] = audio
        val (sync, store) = sync()
        store.bound(key)

        val outcome = sync.sync() as SyncOutcome.Done
        assertEquals(1, outcome.downloaded)
        assertArrayEquals(audio, library.imported.getValue("note-1"))
        // One GETS for each 64 KiB: the 3840-byte window of the stock firmware would need 40.
        val gets = recorder.commands.filter { it.startsWith("+XREC:GETS") }
        assertEquals(
            listOf(
                "+XREC:GETS,0,65536,640,REC_0001.wav",
                "+XREC:GETS,65536,65536,640,REC_0001.wav",
                "+XREC:GETS,131072,18928,640,REC_0001.wav",
            ),
            gets,
        )
        val speed = outcome.speed!!
        assertEquals(150_000L, speed.bytes)
        assertEquals(0, speed.retries)
        val last = store.read().current!!.lastSync!!
        assertEquals(150_000L, last.downloadedBytes)
        assertEquals(speed.millis, last.downloadMillis)
        val expected = last.downloadMillis.takeIf { it > 0 }?.let { 150_000L / it }
        assertEquals(expected, RecorderTexts.speedKilobytesPerSecond(last))
    }

    @Test fun aSyncWithoutDownloadsHasNoSpeed() = runBlocking {
        val (sync, store) = sync()
        store.bound(key)
        assertNull((sync.sync() as SyncOutcome.Done).speed)
        val last = store.read().current!!.lastSync!!
        assertEquals(0L, last.downloadedBytes)
        assertNull(RecorderTexts.speedKilobytesPerSecond(last))
    }

    @Test fun aNewRecordingIsImportedTranscribedAndItsTranscriptGoesBack() = runBlocking {
        val audio = recording(10_000)
        recorder.files["REC_0001.wav"] = audio
        recorder.files["REC_0002.wav"] = recording(44)
        val (sync, store) = sync()
        store.bound(key)

        val first = sync.sync()
        assertDone(SyncOutcome.Done(downloaded = 1, uploaded = 0, skipped = 1, deviceRecording = false), first)
        assertArrayEquals(audio, library.imported.getValue("note-1"))
        assertEquals("REC_0001.wav", library.titles.getValue("note-1"))
        assertEquals(listOf("note-1"), library.transcribed)
        val stored = store.read().current!!
        assertEquals(listOf("note-1"), stored.recordings.map { it.recording.noteId })
        assertNull(stored.partial)
        assertEquals(RecorderSync.OUTCOME_OK, stored.lastSync!!.outcome)
        assertTrue(parts.isEmpty())
        // The clock matches the phone's: the sync reads it and leaves it.
        assertFalse(radio.device.commands.any { it.startsWith("+XREC:RTC,") })

        // The note is transcribed now: the next sync sends the sidecar.
        library.ready["note-1"] = RecorderTranscript("v1", transcript("Standup"))
        assertDone(SyncOutcome.Done(0, 1, 1, false), sync.sync())
        val cwx = CwxReader(radio.device.files.getValue("REC_0001.cwx"))
        assertEquals("Standup", cwx.title)
        assertEquals(listOf("Ada", "Speaker 2"), cwx.speakers.map { it.label })
        assertEquals(listOf("Hello", "there"), cwx.wordsFrom(0).map { it.text })
        val version = store.read().current!!.recordings.single().recording.transcriptUploadedVersion
        assertTrue(version!!.startsWith("v1:"))

        // Nothing new: no download and no upload.
        assertDone(SyncOutcome.Done(0, 0, 1, false), sync.sync())
        assertFalse(radio.device.commands.any { it.startsWith("+XREC:PUT") || it.startsWith("+XREC:GETS") })

        // A renamed speaker in the same variant is a new transcript: it replaces the sidecar.
        library.ready["note-1"] = RecorderTranscript("v1", transcript("Standup", first = "Ada Lovelace"))
        assertDone(SyncOutcome.Done(0, 1, 1, false), sync.sync())
        val renamed = CwxReader(radio.device.files.getValue("REC_0001.cwx"))
        assertEquals(listOf("Ada Lovelace", "Speaker 2"), renamed.speakers.map { it.label })
    }

    @Test fun anUploadOnlySyncSendsTheWaitingTranscriptAndDownloadsNothing() = runBlocking {
        recorder.files["REC_0001.wav"] = recording(10_000)
        val (sync, store) = sync()
        store.bound(key)
        sync.sync()
        assertEquals(emptySet<String>(), sync.pendingUploads())

        library.ready["note-1"] = RecorderTranscript("v1", transcript("Standup"))
        assertEquals(setOf("note-1"), sync.pendingUploads())
        radio.device.files["REC_0002.wav"] = recording(10_000, seed = 2)
        assertDone(SyncOutcome.Done(0, 1, 1, false), sync.sync(SyncMode.UPLOADS))
        assertTrue(radio.device.files.containsKey("REC_0001.cwx"))
        assertEquals(listOf("note-1"), library.imported.keys.toList())
        // Only the first 4 KiB of REC_0001.wav, the check before its first sidecar: REC_0002.wav is not downloaded.
        assertFalse(radio.device.commands.any { it.startsWith("+XREC:GETS") && "REC_0002" in it })
        assertEquals(emptySet<String>(), sync.pendingUploads())

        // A new version waits again; the next full sync downloads the new recording.
        library.ready["note-1"] = RecorderTranscript("v2", transcript("Standup"))
        assertEquals(setOf("note-1"), sync.pendingUploads())
        assertDone(SyncOutcome.Done(1, 1, 0, false), sync.sync())
        assertEquals(emptySet<String>(), sync.pendingUploads())
    }

    @Test fun aTranscriptThatFinishesDuringTheDownloadsGoesInTheSameSync() = runBlocking {
        recorder.files["REC_0001.wav"] = recording(10_000)
        val (sync, store) = sync()
        store.bound(key)
        sync.sync()
        radio.device.files["REC_0002.wav"] = recording(10_000, seed = 2)
        radio.device.beforeGets = { name ->
            if (name == "REC_0002.wav") library.ready["note-1"] = RecorderTranscript("v1", transcript("Standup"))
        }
        assertDone(SyncOutcome.Done(1, 1, 0, false), sync.sync())
        assertTrue(radio.device.files.containsKey("REC_0001.cwx"))
        assertEquals(emptySet<String>(), sync.pendingUploads())
    }

    @Test fun aFullSyncWaitsForAnUploadOnlySyncInsteadOfBeingLost() = runBlocking {
        recorder.files["REC_0001.wav"] = recording(10_000)
        val (sync, store) = sync()
        store.bound(key)
        sync.sync()
        library.ready["note-1"] = RecorderTranscript("v1", transcript("Standup"))
        radio.device.files["REC_0002.wav"] = recording(10_000, seed = 2)
        val putting = CountDownLatch(1)
        val release = CountDownLatch(1)
        radio.device.beforePut = { offset ->
            if (offset == 0L) {
                putting.countDown()
                release.await(HOOK_MS, TimeUnit.MILLISECONDS)
            }
        }
        coroutineScope {
            val uploads = async(Dispatchers.Default) { sync.sync(SyncMode.UPLOADS) }
            assertTrue(putting.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
            val full = async(Dispatchers.Default) { sync.sync() }
            // Another upload-only sync is busy at once; the full one waits.
            assertEquals(SyncOutcome.Busy, sync.sync(SyncMode.UPLOADS))
            delay(100)
            assertFalse(full.isCompleted)
            release.countDown()
            assertDone(SyncOutcome.Done(0, 1, 1, false), uploads.await())
            assertDone(SyncOutcome.Done(1, 0, 0, false), full.await())
        }
        sync.awaitIdle()
        assertEquals(listOf("note-1", "note-2"), library.imported.keys.toList())
    }

    @Test fun aDownloadThatLosesTheLinkResumesAtTheNextSync() = runBlocking {
        val audio = recording(10_000, seed = 7)
        recorder.files["REC_0001.wav"] = audio
        recorder.disconnectAtFrame = 3_840L
        val (sync, store) = sync()
        store.bound(key)

        val first = sync.sync()
        assertEquals(SyncOutcome.Failed(XrecFailure.DISCONNECTED, "The link closed"), first)
        val partial = store.read().current!!.partial!!
        assertEquals("REC_0001.wav", partial.name)
        assertEquals(3_840L, partial.bytes)
        assertTrue(library.imported.isEmpty())

        assertDone(SyncOutcome.Done(1, 0, 0, false), sync.sync())
        // The second link asks for the rest only.
        val gets = radio.device.commands.first { it.startsWith("+XREC:GETS") }
        assertEquals("+XREC:GETS,3840,6160,640,REC_0001.wav", gets)
        assertArrayEquals(audio, library.imported.getValue("note-1"))
        assertNull(store.read().current!!.partial)
    }

    @Test fun aDownloadContinuesFromThePartFileAfterProcessDeath() = runBlocking {
        val audio = recording(10_000, seed = 3)
        recorder.files["REC_0001.wav"] = audio
        val (sync, store) = sync()
        store.bound(key)
        // The process died at 5000 bytes, after the state said 0: the file length is the truth.
        store.updateDevice(DEVICE.address) {
            it.copy(partial = PartialDownload("REC_0001.wav", 10_000, 0))
        }
        val part = File(File(File(root, "downloads"), "001122334455"), "REC_0001.wav.part")
        part.parentFile!!.mkdirs()
        part.writeBytes(audio.copyOf(5_000))

        assertDone(SyncOutcome.Done(1, 0, 0, false), sync.sync())
        assertEquals("+XREC:GETS,5000,5000,640,REC_0001.wav", recorder.commands.first { it.startsWith("+XREC:GETS") })
        assertArrayEquals(audio, library.imported.getValue("note-1"))
    }

    @Test fun nothingIsDownloadedWhileTheRecorderRecords() = runBlocking {
        recorder.recording = true
        recorder.files["REC_0001.wav"] = recording(2_000)
        val (sync, store) = sync()
        store.bound(key)
        assertDone(SyncOutcome.Done(0, 0, 1, true), sync.sync())
        assertTrue(library.imported.isEmpty())
        assertFalse(recorder.commands.any { it.startsWith("+XREC:GETS") })
    }

    @Test fun aRecordingThatStartsDuringTheSyncStopsTheDownloads() = runBlocking {
        recorder.files["REC_0001.wav"] = recording(2_000, seed = 1)
        recorder.files["REC_0002.wav"] = recording(2_000, seed = 2)
        recorder.beforeGets = { recorder.recording = true }
        val (sync, store) = sync()
        store.bound(key)
        // STAT runs again before each download after the first: the second waits for the next sync.
        assertDone(SyncOutcome.Done(1, 0, 1, true), sync.sync())
        assertEquals(1, library.imported.size)
    }

    // Sync while the recorder records (docs/BT_SYNC_SPEED_AND_RECORDING.md S4). ----------------------------------------

    /** A sync with "Sync while the recorder records" [on]. */
    private suspend fun recordingSync(on: Boolean = true, clock: () -> Long = { NOW }) =
        recorderSync(root, radio, library, clock).also { (_, store) ->
            store.bound(key)
            store.update { it.copy(syncWhileRecording = on) }
        }

    /** The firmware that syncs while it records, recording REC_0003.wav (mono); REC_0001 and REC_0002 are finished. */
    private fun recordingWithFinishedFiles() {
        recorder.syncsWhileRecording = true
        recorder.recording = true
        recorder.files["REC_0001.wav"] = recording(20_000, seed = 1)
        recorder.files["REC_0002.wav"] = recording(3_000, seed = 2)
        recorder.files["REC_0003.wav"] = recording(5_000, seed = 3)
    }

    @Test fun whileTheRecorderRecordsTheFinishedRecordingsGoAndTheActiveFileWaits() = runBlocking {
        recordingWithFinishedFiles()
        // The phone's clock is an hour ahead: the recorder does not take a clock while it records.
        val (sync, store) = recordingSync(clock = { NOW + 3_600_000 })

        val outcome = sync.sync() as SyncOutcome.Done
        assertEquals(
            SyncOutcome.Done(2, 0, 1, deviceRecording = true, recordingPoll = true),
            outcome.copy(speed = null),
        )
        assertEquals(listOf("REC_0001.wav", "REC_0002.wav"), library.titles.values.toList())
        assertArrayEquals(recorder.files["REC_0001.wav"], library.imported.getValue("note-1"))
        val commands = recorder.commands.toList()
        assertFalse(commands.any { it.startsWith("+XREC:GETS") && "REC_0003" in it })
        // 16 KiB windows at the recorder's pace while it records.
        assertEquals(
            listOf("+XREC:GETS,0,16384,640,REC_0001.wav", "+XREC:GETS,16384,3616,640,REC_0001.wav"),
            commands.filter { it.startsWith("+XREC:GETS") && "REC_0001" in it },
        )
        // LS comes between two STATs, the STAT of each file is not needed, and no clock is read or set.
        assertEquals(listOf("+XREC:STAT", "+XREC:LS", "+XREC:STAT"), commands.filter { it in LIST_COMMANDS })
        assertFalse(commands.any { it.startsWith("+XREC:RTC") })
        val names = store.read().current!!.recordings.map { it.recording.name }
        assertEquals(listOf("REC_0001.wav", "REC_0002.wav"), names)

        // The recording ends: the next sync takes its file and sets the clock.
        radio.device.recording = false
        assertDone(SyncOutcome.Done(1, 0, 0, false), sync.sync())
        assertEquals("REC_0003.wav", library.titles.getValue("note-3"))
        assertTrue(radio.device.commands.any { it.startsWith("+XREC:RTC,") })
    }

    @Test fun aRecordingThatStopsDuringLsIsListedAgainAndDownloadedWhole() = runBlocking {
        recorder.recording = true
        recorder.files["REC_0001.wav"] = recording(2_000, seed = 1)
        recorder.files["REC_0002.wav"] = recording(3_000, seed = 2)
        val whole = recording(8_000, seed = 3)
        recorder.files["REC_0003.wav"] = whole.copyOf(5_000)
        // The recording stops after LS: its file grows to its final size and the next STAT is idle.
        recorder.afterList = {
            recorder.afterList = null
            recorder.files["REC_0003.wav"] = whole
            recorder.recording = false
        }
        val (sync, store) = sync()
        store.bound(key)

        assertDone(SyncOutcome.Done(3, 0, 0, false), sync.sync())
        // The listing of the recording is not trusted: LS runs again, and the whole file comes once (then the STAT
        // before each download after the first).
        val commands = recorder.commands.filter { it in LIST_COMMANDS }.take(5)
        assertEquals(listOf("+XREC:STAT", "+XREC:LS", "+XREC:STAT", "+XREC:LS", "+XREC:STAT"), commands)
        assertEquals(2, recorder.commands.count { it == "+XREC:LS" })
        assertArrayEquals(whole, library.imported.getValue("note-3"))
        assertEquals("REC_0003.wav", library.titles.getValue("note-3"))
    }

    @Test fun anActiveFileThatSplitsDuringLsIsNotDownloaded() = runBlocking {
        recordingWithFinishedFiles()
        // At 2 GiB the firmware closes REC_0003.wav and goes on in REC_0004.wav after LS.
        recorder.afterList = {
            recorder.afterList = null
            recorder.files["REC_0004.wav"] = recording(1_000, seed = 4)
        }
        val (sync, _) = recordingSync()

        val outcome = sync.sync() as SyncOutcome.Done
        // Listed again with the new active file: REC_0003.wav is finished now and goes whole; REC_0004.wav waits.
        assertEquals(3, outcome.downloaded)
        assertEquals(2, recorder.commands.count { it == "+XREC:LS" })
        assertFalse(recorder.commands.any { it.startsWith("+XREC:GETS") && "REC_0004" in it })
    }

    @Test fun anOlderFirmwareWithoutTheRecordingIndexStillSkipsEverythingWhileItRecords() = runBlocking {
        recordingWithFinishedFiles()
        recorder.syncsWhileRecording = false
        val (sync, _) = recordingSync()
        assertDone(SyncOutcome.Done(0, 0, 3, true), sync.sync())
        assertTrue(library.imported.isEmpty())
        assertFalse(recorder.commands.any { it.startsWith("+XREC:GETS") })
    }

    @Test fun withTheSettingOffNothingIsDownloadedWhileTheRecorderRecords() = runBlocking {
        recordingWithFinishedFiles()
        val (sync, _) = recordingSync(on = false)
        assertDone(SyncOutcome.Done(0, 0, 3, true), sync.sync())
        assertTrue(library.imported.isEmpty())
        assertFalse(recorder.commands.any { it.startsWith("+XREC:GETS") })
    }

    @Test fun withTheSettingOffARecordingThatStartsStillStopsTheDownloads() = runBlocking {
        recorder.syncsWhileRecording = true
        recorder.files["REC_0001.wav"] = recording(2_000, seed = 1)
        recorder.files["REC_0002.wav"] = recording(2_000, seed = 2)
        recorder.files["REC_0003.wav"] = recording(2_000, seed = 3)
        recorder.beforeGets = { recorder.recording = true }
        val (sync, _) = recordingSync(on = false)
        assertDone(SyncOutcome.Done(1, 0, 2, true), sync.sync())
        assertEquals(1, library.imported.size)
    }

    @Test fun aLaterEndsTheSyncAtOnceAndTheNextSyncContinuesWhereItStopped() = runBlocking {
        recordingWithFinishedFiles()
        // The recorder answers LATER in place of the third frame of the first file (a stereo recording started).
        recorder.laterAtFrame = 1_280L
        val (sync, store) = recordingSync()

        // No wait on the open link: the sync ends and disconnects.
        assertDone(SyncOutcome.Done(0, 0, 3, true, recordingPoll = true), sync.sync())
        assertEquals(1, recorder.commands.count { it.startsWith("+XREC:GETS") })
        assertEquals(PartialDownload("REC_0001.wav", 20_000, 1_280), store.read().current!!.partial)
        assertEquals(1, radio.opens)

        val outcome = sync.sync() as SyncOutcome.Done
        assertEquals(2, outcome.downloaded)
        assertEquals(0, outcome.failed)
        val gets = radio.device.commands.first { it.startsWith("+XREC:GETS") && "REC_0001" in it }
        assertEquals("+XREC:GETS,1280,16384,640,REC_0001.wav", gets)
        assertArrayEquals(recorder.files["REC_0001.wav"], library.imported.getValue("note-1"))
    }

    @Test fun aStereoRecordingThatStartsDuringTheSyncDefersTheDownloadsToTheNextSync() = runBlocking {
        recordingWithFinishedFiles()
        // A stereo recording starts after STAT: each GETS answers LATER.
        recorder.beforeGets = { recorder.stereo = true }
        val (sync, store) = recordingSync()

        assertDone(SyncOutcome.Done(0, 0, 3, true, recordingPoll = true), sync.sync())
        // The first LATER defers this file and the others: one GETS, no wait on the link.
        assertEquals(1, recorder.commands.count { it.startsWith("+XREC:GETS") })
        assertTrue(library.imported.isEmpty())
        assertEquals(PartialDownload("REC_0001.wav", 20_000, 0), store.read().current!!.partial)
        assertEquals(RecorderSync.OUTCOME_OK, store.read().current!!.lastSync!!.outcome)

        radio.device.beforeGets = null
        radio.device.stereo = false
        radio.device.recording = false
        assertDone(SyncOutcome.Done(3, 0, 0, false), sync.sync())
        assertEquals(3, library.imported.size)
    }

    @Test fun aBusyFileIsSkippedForThisSyncAndTheOthersGo() = runBlocking {
        recorder.syncsWhileRecording = true
        recorder.files["REC_0001.wav"] = recording(2_000, seed = 1)
        recorder.files["REC_0002.wav"] = recording(2_000, seed = 2)
        // A recording starts after STAT and takes REC_0002.wav: the recorder refuses it with BUSY.
        recorder.beforeGets = { recorder.recording = true }
        val (sync, store) = recordingSync()

        assertDone(SyncOutcome.Done(1, 0, 0, false), sync.sync())
        assertEquals(listOf("REC_0001.wav"), library.titles.values.toList())
        assertNull(store.read().current!!.partial)
        assertTrue(parts.isEmpty())

        radio.device.beforeGets = null
        radio.device.recording = false
        assertDone(SyncOutcome.Done(1, 0, 0, false), sync.sync())
        assertEquals(listOf("REC_0001.wav", "REC_0002.wav"), library.titles.values.toList())
    }

    @Test fun aTranscriptThatTheRecordingRefusesWaitsWithoutAFailure() = runBlocking {
        recorder.syncsWhileRecording = true
        recorder.files["REC_0001.wav"] = recording(3_000)
        val (sync, store) = recordingSync()
        sync.sync()
        library.ready["note-1"] = RecorderTranscript("v1", transcript("Standup"))
        // A recording starts while the sidecar goes: the recorder answers LATER to its chunk.
        radio.device.beforePut = { radio.device.recording = true }

        assertDone(SyncOutcome.Done(0, 0, 0, false, deferredUploads = 1), sync.sync(SyncMode.UPLOADS))
        val stored = store.read().current!!.recordings.single()
        assertNull(stored.recording.transcriptUploadedVersion)
        assertNull(stored.failure)
        assertEquals(setOf("note-1"), sync.pendingUploads())

        // While it records, the next sync does not try: the transcript waits.
        radio.device.beforePut = null
        assertDone(SyncOutcome.Done(0, 0, 0, true, deferredUploads = 1), sync.sync(SyncMode.UPLOADS))
        assertEquals(0, radio.device.laterReplies)
        assertFalse(radio.device.commands.any { it.startsWith("+XREC:PUT") })

        // After the recording the transcript goes.
        radio.device.recording = false
        assertDone(SyncOutcome.Done(0, 1, 0, false), sync.sync(SyncMode.UPLOADS))
        assertTrue(radio.device.files.containsKey("REC_0001.cwx"))
    }

    @Test fun aRefusedKeyAsksForANewBindingAndStopsTheSyncs() = runBlocking {
        val (sync, store) = sync()
        store.bound(ByteArray(XrecAuth.KEY_BYTES) { 9 })
        assertEquals(SyncOutcome.RebindNeeded, sync.sync())
        val state = store.read().current!!
        assertTrue(state.rebindNeeded)
        assertEquals(RecorderSync.OUTCOME_REBIND, state.lastSync!!.outcome)
        // No more connections until the recorder is bound again.
        assertEquals(SyncOutcome.RebindNeeded, sync.sync())
        assertEquals(1, radio.opens)
    }

    @Test fun anOldFirmwareIsAskedForTranscriptsAgainADayLater() = runBlocking {
        recorder.recordTarget = false
        recorder.files["REC_0001.wav"] = recording(3_000)
        var now = NOW
        val (sync, store) = sync(clock = { now })
        store.bound(key)
        assertDone(SyncOutcome.Done(1, 0, 0, false), sync.sync())
        library.ready["note-1"] = RecorderTranscript("v1", transcript("Old"))

        assertDone(SyncOutcome.Done(0, 0, 0, false), sync.sync())
        assertEquals("openfw_v5", store.read().current!!.transcriptsUnsupportedFirmware)
        assertFalse(radio.device.files.containsKey("REC_0001.cwx"))
        // Within the day the transcript does not wait: the push does not call the recorder for it.
        assertEquals(emptySet<String>(), sync.pendingUploads())
        // The recorder gets the new firmware, which keeps the same name in PING. Within the day nothing is asked.
        radio.device.recordTarget = true
        now += TranscriptUploadProbe.RETRY_MS - 1
        assertDone(SyncOutcome.Done(0, 0, 0, false), sync.sync())
        assertFalse(radio.device.commands.any { it.startsWith("+XREC:PUT") })
        // A day later the app asks again, and the firmware takes the transcript.
        now += 1
        assertEquals(setOf("note-1"), sync.pendingUploads())
        assertDone(SyncOutcome.Done(0, 1, 0, false), sync.sync())
        assertTrue(radio.device.files.containsKey("REC_0001.cwx"))
        assertNull(store.read().current!!.transcriptsUnsupportedFirmware)
    }

    @Test fun aSidecarWhoseRecordingWentIsSkippedAndTheOthersGo() = runBlocking {
        recorder.files["REC_0001.wav"] = recording(3_000, seed = 1)
        recorder.files["REC_0002.wav"] = recording(3_000, seed = 2)
        val (sync, store) = sync()
        store.bound(key)
        assertDone(SyncOutcome.Done(2, 0, 0, false), sync.sync())
        library.ready["note-1"] = RecorderTranscript("v1", transcript("One"))
        library.ready["note-2"] = RecorderTranscript("v1", transcript("Two"))
        // REC_0001.wav goes after LS, before its sidecar: the recorder answers ERR,NOENT.
        radio.device.beforePut = { offset -> if (offset == 0L) radio.device.files.remove("REC_0001.wav") }

        assertDone(SyncOutcome.Done(0, 1, 0, false), sync.sync())
        val recordings = store.read().current!!.recordings.associateBy { it.recording.name }
        assertEquals(RecorderSync.UPLOAD_FAILURE + "NOT_FOUND", recordings.getValue("REC_0001.wav").failure)
        assertNull(recordings.getValue("REC_0002.wav").failure)
        assertTrue(radio.device.files.containsKey("REC_0002.cwx"))
        assertFalse(radio.device.files.containsKey("REC_0001.cwx"))
    }

    @Test fun aSidecarNameThatTheRecorderRefusesIsNotSentAgainUntilItChanges() = runBlocking {
        recorder.files["REC_0001.wav"] = recording(3_000)
        recorder.refusedNames += "REC_0001.cwx"
        val (sync, store) = sync()
        store.bound(key)
        sync.sync()
        library.ready["note-1"] = RecorderTranscript("v1", transcript("One"))
        assertDone(SyncOutcome.Done(0, 0, 0, false), sync.sync())
        val stored = store.read().current!!.recordings.single()
        assertEquals(RecorderSync.UPLOAD_FAILURE + "BAD_NAME", stored.failure)
        assertTrue(stored.recording.transcriptRefusedVersion!!.startsWith("v1:"))

        // The same version is not sent again.
        sync.sync()
        assertFalse(radio.device.commands.any { it.startsWith("+XREC:PUT") })
        // A new version is.
        radio.device.refusedNames.clear()
        library.ready["note-1"] = RecorderTranscript("v2", transcript("One"))
        assertDone(SyncOutcome.Done(0, 1, 0, false), sync.sync())
        assertNull(store.read().current!!.recordings.single().recording.transcriptRefusedVersion)
    }

    @Test fun aRecordingThatTheRecorderCannotReadDoesNotStopTheOthers() = runBlocking {
        recorder.files["REC_0001.wav"] = recording(3_000, seed = 1)
        recorder.files["REC_0002.wav"] = recording(3_000, seed = 2)
        recorder.unreadable += "REC_0001.wav"
        val (sync, store) = sync()
        store.bound(key)
        assertDone(SyncOutcome.Done(1, 0, 0, false, failed = 1), sync.sync())
        assertEquals(listOf("REC_0002.wav"), store.read().current!!.recordings.map { it.recording.name })
        // The stray .part file of the failed download goes at the end of the sync.
        assertTrue(parts.isEmpty())

        // The next sync tries it again.
        radio.device.unreadable.clear()
        assertDone(SyncOutcome.Done(1, 0, 0, false), sync.sync())
        assertEquals(2, library.imported.size)
    }

    @Test fun aRecordingRemovedBetweenTheListingAndItsDownloadIsSkipped() = runBlocking {
        recorder.files["REC_0001.wav"] = recording(3_000, seed = 1)
        recorder.files["REC_0002.wav"] = recording(3_000, seed = 2)
        recorder.beforeGets = { name -> if (name == "REC_0001.wav") recorder.files.remove(name) }
        val (sync, store) = sync()
        store.bound(key)
        assertDone(SyncOutcome.Done(1, 0, 0, false), sync.sync())
        val state = store.read().current!!
        assertEquals(listOf("REC_0002.wav"), state.recordings.map { it.recording.name })
        assertNull(state.partial)
        assertTrue(parts.isEmpty())
    }

    @Test fun anImportRefusedForLackOfSpaceIsTriedAgainWithoutDownloadingAgain() = runBlocking {
        recorder.files["REC_0001.wav"] = recording(3_000)
        library.reject = "SPACE"
        val (sync, store) = sync()
        store.bound(key)
        assertDone(SyncOutcome.Done(0, 0, 0, false, failed = 1), sync.sync())
        val state = store.read().current!!
        assertTrue(state.recordings.isEmpty())
        assertEquals(PartialDownload("REC_0001.wav", 3_000, 3_000), state.partial)

        library.reject = null
        assertDone(SyncOutcome.Done(1, 0, 0, false), sync.sync())
        assertFalse(radio.device.commands.any { it.startsWith("+XREC:GETS") })
        assertEquals(listOf("note-1"), store.read().current!!.recordings.map { it.recording.noteId })
    }

    @Test fun aRejectedImportIsRememberedAndNotDownloadedAgain() = runBlocking {
        recorder.files["REC_0001.wav"] = recording(3_000)
        library.reject = "WAV"
        val (sync, store) = sync()
        store.bound(key)
        assertDone(SyncOutcome.Done(1, 0, 0, false), sync.sync())
        val stored = store.read().current!!.recordings.single()
        assertNull(stored.recording.noteId)
        assertEquals(RecorderSync.IMPORT_FAILURE + "WAV", stored.failure)
        assertDone(SyncOutcome.Done(0, 0, 0, false), sync.sync())
        assertFalse(radio.device.commands.any { it.startsWith("+XREC:GETS") })
    }

    @Test fun anImportThatStoppedBeforeItsTranscriptionRequestIsTranscribedAtTheNextSync() = runBlocking {
        recorder.files["REC_0001.wav"] = recording(3_000)
        library.failNextTranscribe = true
        val (sync, store) = sync()
        store.bound(key)
        // The failure ends this sync; it does not escape it.
        assertEquals(SyncOutcome.Failed(null, "IllegalStateException"), sync.sync())
        assertTrue(store.read().current!!.recordings.isEmpty())
        assertTrue(library.transcribed.isEmpty())

        // The import finds the note (Existing), which has no transcription yet: the sync asks for it.
        assertDone(SyncOutcome.Done(1, 0, 0, false), sync.sync())
        assertEquals(listOf("note-1"), library.transcribed)
        assertEquals(1, library.imported.size)
        assertFalse(radio.device.commands.any { it.startsWith("+XREC:GETS") })

        // A note found again that is transcribed already is not transcribed twice.
        store.updateDevice(DEVICE.address) { it.copy(recordings = emptyList()) }
        sync.sync()
        assertEquals(listOf("note-1"), library.transcribed)
    }

    @Test fun aRecordingWithTheSameNameAndAnotherSizeIsANewOne() = runBlocking {
        recorder.files["REC_0001.wav"] = recording(3_000, seed = 1)
        val (sync, store) = sync()
        store.bound(key)
        sync.sync()
        // The card was wiped and the recorder numbered from 1 again.
        radio.device.files["REC_0001.wav"] = recording(4_000, seed = 2)
        assertDone(SyncOutcome.Done(1, 0, 0, false), sync.sync())
        val recordings = store.read().current!!.recordings.map { it.recording }
        assertEquals(listOf(4_000L), recordings.map { it.size })
        assertEquals(listOf("note-2"), recordings.map { it.noteId })
    }

    @Test fun aNewRecordingWithTheSameNameAndSizeGetsNoOldTranscript() = runBlocking {
        recorder.files["REC_0001.wav"] = recording(3_000, seed = 1)
        val (sync, store) = sync()
        store.bound(key)
        sync.sync()
        library.ready["note-1"] = RecorderTranscript("v1", transcript("Old"))
        assertDone(SyncOutcome.Done(0, 1, 0, false), sync.sync())
        // The card was wiped: a new recording with the same name and size, and no sidecar.
        radio.device.files.clear()
        radio.device.files["REC_0001.wav"] = recording(3_000, seed = 9)

        assertDone(SyncOutcome.Done(1, 0, 0, false), sync.sync())
        assertFalse(radio.device.files.containsKey("REC_0001.cwx"))
        assertEquals(listOf("note-2"), store.read().current!!.recordings.map { it.recording.noteId })
    }

    @Test fun anUnreadableSuspectGetsNoOldTranscriptAndIsCheckedAgainLater() = runBlocking {
        recorder.files["REC_0001.wav"] = recording(3_000, seed = 1)
        val (sync, store) = sync()
        store.bound(key)
        sync.sync()
        library.ready["note-1"] = RecorderTranscript("v1", transcript("Old"))
        assertDone(SyncOutcome.Done(0, 1, 0, false), sync.sync())

        // The card was wiped: a new recording with the same name and size, no sidecar, and a transient SD read error
        // when the sync tries to compare its first 4 KiB.
        radio.device.files.clear()
        radio.device.files["REC_0001.wav"] = recording(3_000, seed = 9)
        radio.device.unreadable += "REC_0001.wav"
        assertDone(SyncOutcome.Done(0, 0, 0, false), sync.sync())
        assertFalse(radio.device.files.containsKey("REC_0001.cwx"))

        // Once the card reads cleanly again, the next sync compares the head, drops the old record and imports the new
        // recording instead of leaving the old transcript attached to it.
        radio.device.unreadable.clear()
        assertDone(SyncOutcome.Done(1, 0, 0, false), sync.sync())
        assertFalse(radio.device.files.containsKey("REC_0001.cwx"))
        assertEquals(listOf("note-2"), store.read().current!!.recordings.map { it.recording.noteId })
    }

    @Test fun aWrongClockIsSetToThePhoneTime() = runBlocking {
        val (sync, store) = sync(clock = { NOW + 3_600_000L })
        store.bound(key)
        assertDone(SyncOutcome.Done(0, 0, 0, false), sync.sync())
        assertTrue(recorder.commands.contains("+XREC:RTC,2026,11,25,11,11,12,2"))
    }

    @Test fun anUnreachableRecorderIsAQuietOutcome() = runBlocking {
        radio.reachable = false
        val (sync, store) = sync()
        store.bound(key)
        assertEquals(SyncOutcome.Unreachable, sync.sync())
        assertEquals(RecorderSync.OUTCOME_UNREACHABLE, store.read().current!!.lastSync!!.outcome)
        radio.readiness = RadioReadiness.OFF
        assertEquals(SyncOutcome.Unavailable(RadioReadiness.OFF), sync.sync())
        assertFalse(sync.canSync())
        store.update { it.copy(binding = null) }
        assertEquals(SyncOutcome.NotBound, sync.sync())
    }

    @Test fun aStalledLinkIsClosedByTheWatchdog() = runBlocking {
        radio.stall = true
        val (sync, store) = sync(stallMillis = 300)
        store.bound(key)
        val outcome = withTimeout(TIMEOUT_MS) { sync.sync() }
        assertEquals(XrecFailure.DISCONNECTED, (outcome as SyncOutcome.Failed).failure)
    }

    @Test fun aStoreThatCannotBeWrittenEndsTheSyncWithoutAnException() = runBlocking {
        recorder.files["REC_0001.wav"] = recording(3_000)
        val (sync, store) = sync()
        store.bound(key)
        assertTrue(root.setWritable(false))
        try {
            assertTrue(sync.sync() is SyncOutcome.Failed)
        } finally {
            root.setWritable(true)
        }
    }

    @Test fun bindingAsksForThePinAgainAfterAWrongOneAndStoresOnlyTheWrappedKey() = runBlocking {
        val fresh = FakeRecorder(pairingPin = "123456")
        radio.device = fresh
        val (sync, store) = sync()
        // An old refusal of transcripts is forgotten by a new binding.
        store.updateDevice(DEVICE.address) {
            it.copy(transcriptsUnsupportedFirmware = "openfw_v5", transcriptsUnsupportedAt = NOW)
        }
        val asked = mutableListOf<Boolean>()
        val outcome = sync.bind(DEVICE) { wrong ->
            asked += wrong
            if (wrong) "123456" else "000000"
        }
        assertEquals(BindOutcome.Bound, outcome)
        assertEquals(listOf(false, true), asked)
        val binding = store.read().binding!!
        assertEquals(DEVICE, binding.device)
        assertArrayEquals(fresh.key, FakeVault().unwrap(binding.wrappedKey))
        assertTrue(binding.wrappedKey.startsWith(FakeVault.PREFIX))
        assertNull(store.read().current!!.transcriptsUnsupportedFirmware)
        // The new key authenticates the next session.
        assertTrue(sync.sync() is SyncOutcome.Done)
    }

    @Test fun bindingNeedsTheOpenPairingWindowAndABondTheRecorderKnows() = runBlocking {
        radio.device = FakeRecorder()
        val (sync, store) = sync()
        assertEquals(BindOutcome.PairingClosed, sync.bind(DEVICE) { "123456" })
        assertNull(store.read().binding)
        radio.bond = BondResult.FAILED
        assertEquals(BindOutcome.NotBonded, sync.bind(DEVICE) { "123456" })
        // The recorder refuses the link: after a new bond it is out of reach, after an old one the bond is stale.
        radio.reachable = false
        radio.bond = BondResult.BONDED
        assertEquals(BindOutcome.Unreachable, sync.bind(DEVICE) { "123456" })
        radio.bond = BondResult.BONDED_BEFORE
        assertEquals(BindOutcome.StaleBond, sync.bind(DEVICE) { "123456" })
    }

    private companion object {
        /** The commands that read the recorder's state and its listing. */
        val LIST_COMMANDS = setOf("+XREC:STAT", "+XREC:LS")

        const val TIMEOUT_MS = 10_000L

        /** Shorter than the reply timeout of [TEST_TIMEOUTS]: a hook that blocks the fake recorder longer fails the link. */
        const val HOOK_MS = 1_500L
    }
}
