package io.codemyriad.cassini.recorder

import io.codemyriad.cassini.core.recorder.CwxReader
import io.codemyriad.cassini.core.recorder.CwxSpeaker
import io.codemyriad.cassini.core.recorder.CwxTranscript
import io.codemyriad.cassini.core.recorder.CwxWord
import io.codemyriad.cassini.core.recorder.FakeRecorder
import io.codemyriad.cassini.core.recorder.XrecAuth
import io.codemyriad.cassini.core.recorder.XrecFailure
import io.codemyriad.cassini.data.recorder.RecorderStore
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

    private fun sync(clock: () -> Long = { NOW }): Pair<RecorderSync, RecorderStore> =
        recorderSync(root, radio, library, clock)

    private fun transcript(title: String) = CwxTranscript(
        title,
        2_000,
        listOf(CwxSpeaker("Ada", 0x679CFF), CwxSpeaker("Speaker 2", 0xB0B4BC)),
        listOf(CwxWord(0, 400, 0, "Hello"), CwxWord(500, 900, 1, "there")),
    )

    @Test fun aNewRecordingIsImportedTranscribedAndItsTranscriptGoesBack() = runBlocking {
        val audio = recording(10_000)
        recorder.files["REC_0001.wav"] = audio
        recorder.files["REC_0002.wav"] = recording(44)
        val (sync, store) = sync()
        store.bound(key)

        val first = sync.sync()
        assertEquals(SyncOutcome.Done(downloaded = 1, uploaded = 0, skipped = 1, deviceRecording = false), first)
        assertArrayEquals(audio, library.imported.getValue("note-1"))
        assertEquals("REC_0001.wav", library.titles.getValue("note-1"))
        assertEquals(listOf("note-1"), library.transcribed)
        val stored = store.read().current!!
        assertEquals(listOf("note-1"), stored.recordings.map { it.recording.noteId })
        assertNull(stored.partial)
        assertEquals(RecorderSync.OUTCOME_OK, stored.lastSync!!.outcome)
        assertTrue(File(root, "downloads").walk().none { it.isFile })
        // The clock matches the phone's: the sync reads it and leaves it.
        assertFalse(radio.device.commands.any { it.startsWith("+XREC:RTC,") })

        // The note is transcribed now: the next sync sends the sidecar.
        library.ready["note-1"] = RecorderTranscript("v1", transcript("Standup"))
        assertEquals(SyncOutcome.Done(0, 1, 1, false), sync.sync())
        val cwx = CwxReader(radio.device.files.getValue("REC_0001.cwx"))
        assertEquals("Standup", cwx.title)
        assertEquals(listOf("Ada", "Speaker 2"), cwx.speakers.map { it.label })
        assertEquals(listOf("Hello", "there"), cwx.wordsFrom(0).map { it.text })
        val version = store.read().current!!.recordings.single().recording.transcriptUploadedVersion
        assertTrue(version!!.startsWith("v1:"))

        // Nothing new: no download and no upload.
        assertEquals(SyncOutcome.Done(0, 0, 1, false), sync.sync())
        assertFalse(radio.device.commands.any { it.startsWith("+XREC:PUT") || it.startsWith("+XREC:GETS") })

        // A renamed speaker is a new transcript: it replaces the sidecar.
        library.ready["note-1"] = RecorderTranscript("v1", transcript("Standup, renamed"))
        assertEquals(SyncOutcome.Done(0, 1, 1, false), sync.sync())
        assertEquals("Standup, renamed", CwxReader(radio.device.files.getValue("REC_0001.cwx")).title)
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

        assertEquals(SyncOutcome.Done(1, 0, 0, false), sync.sync())
        // The second link asks for the rest only.
        val gets = radio.device.commands.first { it.startsWith("+XREC:GETS") }
        assertEquals("+XREC:GETS,3840,3840,640,REC_0001.wav", gets)
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

        assertEquals(SyncOutcome.Done(1, 0, 0, false), sync.sync())
        assertEquals("+XREC:GETS,5000,3840,640,REC_0001.wav", recorder.commands.first { it.startsWith("+XREC:GETS") })
        assertArrayEquals(audio, library.imported.getValue("note-1"))
    }

    @Test fun nothingIsDownloadedWhileTheRecorderRecords() = runBlocking {
        recorder.recording = true
        recorder.files["REC_0001.wav"] = recording(2_000)
        val (sync, store) = sync()
        store.bound(key)
        assertEquals(SyncOutcome.Done(0, 0, 1, true), sync.sync())
        assertTrue(library.imported.isEmpty())
        assertFalse(recorder.commands.any { it.startsWith("+XREC:GETS") })
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

    @Test fun anOldFirmwareRefusesTranscriptsOnceAndIsNotAskedAgain() = runBlocking {
        recorder.recordTarget = false
        recorder.files["REC_0001.wav"] = recording(3_000)
        val (sync, store) = sync()
        store.bound(key)
        assertEquals(SyncOutcome.Done(1, 0, 0, false), sync.sync())
        library.ready["note-1"] = RecorderTranscript("v1", transcript("Old"))

        assertEquals(SyncOutcome.Done(0, 0, 0, false), sync.sync())
        assertEquals("openfw_v5", store.read().current!!.transcriptsUnsupportedFirmware)
        assertFalse(radio.device.files.containsKey("REC_0001.cwx"))
        // The same firmware is not asked again: an upload now would succeed, and none runs.
        radio.device.recordTarget = true
        assertEquals(SyncOutcome.Done(0, 0, 0, false), sync.sync())
        assertFalse(radio.device.files.containsKey("REC_0001.cwx"))
        assertNull(store.read().current!!.recordings.single().recording.transcriptUploadedVersion)
    }

    @Test fun aRejectedImportIsRememberedAndNotDownloadedAgain() = runBlocking {
        recorder.files["REC_0001.wav"] = recording(3_000)
        library.reject = "WAV"
        val (sync, store) = sync()
        store.bound(key)
        assertEquals(SyncOutcome.Done(1, 0, 0, false), sync.sync())
        val stored = store.read().current!!.recordings.single()
        assertNull(stored.recording.noteId)
        assertEquals(RecorderSync.IMPORT_FAILURE + "WAV", stored.failure)
        assertEquals(SyncOutcome.Done(0, 0, 0, false), sync.sync())
        assertFalse(radio.device.commands.any { it.startsWith("+XREC:GETS") })
    }

    @Test fun aRecordingWithTheSameNameAndAnotherSizeIsANewOne() = runBlocking {
        recorder.files["REC_0001.wav"] = recording(3_000, seed = 1)
        val (sync, store) = sync()
        store.bound(key)
        sync.sync()
        // The card was wiped and the recorder numbered from 1 again.
        radio.device.files["REC_0001.wav"] = recording(4_000, seed = 2)
        assertEquals(SyncOutcome.Done(1, 0, 0, false), sync.sync())
        val recordings = store.read().current!!.recordings.map { it.recording }
        assertEquals(listOf(4_000L), recordings.map { it.size })
        assertEquals(listOf("note-2"), recordings.map { it.noteId })
    }

    @Test fun aWrongClockIsSetToThePhoneTime() = runBlocking {
        val (sync, store) = sync(clock = { NOW + 3_600_000L })
        store.bound(key)
        assertEquals(SyncOutcome.Done(0, 0, 0, false), sync.sync())
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
        store.update { it.copy(binding = null) }
        assertEquals(SyncOutcome.NotBound, sync.sync())
    }

    @Test fun bindingAsksForThePinAgainAfterAWrongOneAndStoresOnlyTheWrappedKey() = runBlocking {
        val fresh = FakeRecorder(pairingPin = "123456")
        radio.device = fresh
        val (sync, store) = sync()
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
        // The new key authenticates the next session.
        assertTrue(sync.sync() is SyncOutcome.Done)
    }

    @Test fun bindingNeedsTheOpenPairingWindow() = runBlocking {
        radio.device = FakeRecorder()
        val (sync, store) = sync()
        assertEquals(BindOutcome.PairingClosed, sync.bind(DEVICE) { "123456" })
        assertNull(store.read().binding)
        radio.bondOk = false
        assertEquals(BindOutcome.NotBonded, sync.bind(DEVICE) { "123456" })
    }
}
