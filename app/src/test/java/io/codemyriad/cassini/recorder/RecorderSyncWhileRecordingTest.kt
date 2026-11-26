package io.codemyriad.cassini.recorder

import io.codemyriad.cassini.core.recorder.CwxSpeaker
import io.codemyriad.cassini.core.recorder.CwxTranscript
import io.codemyriad.cassini.core.recorder.CwxWord
import io.codemyriad.cassini.core.recorder.FakeRecorder
import io.codemyriad.cassini.core.recorder.XrecAuth
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

/**
 * The recorder sync while the recorder records (docs/recorder-sync.md, "Sync while the recorder records"; S4) over
 * [FakeRecorder]. Split from [RecorderSyncTest], which has the same fixtures.
 */
@RunWith(RobolectricTestRunner::class)
class RecorderSyncWhileRecordingTest {
    @get:Rule val folder = TemporaryFolder()

    private val key = ByteArray(XrecAuth.KEY_BYTES) { it.toByte() }
    private val recorder = FakeRecorder(key = key.copyOf())
    private val radio = FakeRadio(recorder)
    private val library = FakeLibrary()
    private val root: File get() = folder.root

    @After fun closeLink() = radio.device.close()

    private fun sync() = recorderSync(root, radio, library)

    private fun transcript(title: String) = CwxTranscript(
        title,
        2_000,
        listOf(CwxSpeaker("Ada", 0x679CFF), CwxSpeaker("Speaker 2", 0xB0B4BC)),
        listOf(CwxWord(0, 400, 0, "Hello"), CwxWord(500, 900, 1, "there")),
    )

    private val parts: List<File> get() = File(root, "downloads").walk().filter { it.isFile }.toList()

    /** The counts of a [SyncOutcome.Done]; its measured [SyncOutcome.Done.speed] varies from run to run. */
    private fun assertDone(expected: SyncOutcome.Done, actual: SyncOutcome) =
        assertEquals(expected, (actual as? SyncOutcome.Done)?.copy(speed = null) ?: actual)

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

    private companion object {
        /** The commands that read the recorder's state and its listing. */
        val LIST_COMMANDS = setOf("+XREC:STAT", "+XREC:LS")
    }
}
