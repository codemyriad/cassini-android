package io.codemyriad.cassini.recorder

import io.codemyriad.cassini.core.recorder.XrecReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlannerTest {
    private val idle = XrecReply.Status(80, 3900, 0, false, 16, 0, 1000, 7000)
    private val recording = idle.copy(media = 33, recordingSeconds = 12)

    private fun file(name: String, size: Long) = XrecReply.Entry(size, name)

    @Test fun downloadsNewRecordingsAndSkipsEmptyAndOtherFiles() {
        val listing = listOf(
            file("REC_0001.wav", 1_000),
            file("REC_0002.wav", 44),
            file("REC_0001.cwx", 300),
            file("notes.txt", 10),
            file("REC_0003.WAV", 45),
        )
        val plan = SyncPlanner.plan(listing, idle, RecorderSyncState(), emptyMap())
        assertEquals(listOf("REC_0001.wav", "REC_0003.WAV"), plan.downloads.map { it.name })
        assertEquals(
            listOf(SkippedFile("REC_0002.wav", SkipReason.EMPTY), SkippedFile("notes.txt", SkipReason.NOT_WAV)),
            plan.skipped,
        )
        assertTrue(plan.uploads.isEmpty())
        assertFalse(plan.deviceRecording)
    }

    @Test fun skipsKnownRecordings() {
        val state = RecorderSyncState(listOf(SyncedRecording("REC_0001.wav", 1_000, "n1")))
        val plan = SyncPlanner.plan(listOf(file("REC_0001.wav", 1_000)), idle, state, emptyMap())
        assertTrue(plan.downloads.isEmpty())
        assertTrue(plan.skipped.isEmpty())
    }

    @Test fun aReusedNameWithAnotherSizeIsANewRecording() {
        val old = SyncedRecording("REC_0001.wav", 1_000, "n1", transcriptUploadedVersion = "v1")
        val state = RecorderSyncState(listOf(old))
        val plan = SyncPlanner.plan(listOf(file("REC_0001.wav", 2_000)), idle, state, mapOf("n1" to "v2"))
        assertEquals(listOf(PlannedDownload("REC_0001.wav", 2_000)), plan.downloads)
        // The old note's transcript does not go to the new recording.
        assertTrue(plan.uploads.isEmpty())
    }

    @Test fun noDownloadWhileTheRecorderRecords() {
        val listing = listOf(file("REC_0001.wav", 1_000), file("REC_0002.wav", 900))
        val plan = SyncPlanner.plan(listing, recording, RecorderSyncState(), emptyMap())
        assertTrue(plan.downloads.isEmpty())
        assertTrue(plan.deviceRecording)
        assertEquals(setOf(SkipReason.RECORDING), plan.skipped.map { it.reason }.toSet())
    }

    /** A recorder that names the file it records (STAT `rec_idx` 3) and paces a mono recording. */
    private val namedRecording = recording.copy(recordingIndex = 3, paceMillis = 342)

    private val recordingListing = listOf(
        file("REC_0001.wav", 1_000),
        file("REC_0002.wav", 900),
        file("REC_0003.wav", 700),
        file("REC_0004.wav", 44),
        file("talk.wav", 2_000),
    )

    @Test fun whileTheRecorderRecordsOnlyTheActiveFileWaits() {
        val plan = SyncPlanner.plan(recordingListing, namedRecording, RecorderSyncState(), emptyMap(), true)
        assertEquals(listOf("REC_0001.wav", "REC_0002.wav", "talk.wav"), plan.downloads.map { it.name })
        assertEquals(
            listOf(SkippedFile("REC_0003.wav", SkipReason.ACTIVE), SkippedFile("REC_0004.wav", SkipReason.EMPTY)),
            plan.skipped,
        )
        assertTrue(plan.deviceRecording)
        assertTrue(plan.whileRecording)
    }

    @Test fun afterTheNumberingWrapsTheFinishedRecordingsWithHigherNumbersGoToo() {
        // The card held REC_9999.wav: the firmware records REC_0003.wav, the lowest free number.
        val listing = listOf(file("REC_0002.wav", 900), file("REC_0003.wav", 700), file("REC_9999.wav", 1_000))
        val plan = SyncPlanner.plan(listing, namedRecording, RecorderSyncState(), emptyMap(), true)
        assertEquals(listOf("REC_0002.wav", "REC_9999.wav"), plan.downloads.map { it.name })
        assertEquals(listOf(SkippedFile("REC_0003.wav", SkipReason.ACTIVE)), plan.skipped)
    }

    @Test fun theActiveFileIsMatchedByItsNumberInAnyCase() {
        val listing = listOf(file("rec_3.WAV", 1_000), file("REC_00002.wav", 1_000))
        val plan = SyncPlanner.plan(listing, namedRecording, RecorderSyncState(), emptyMap(), true)
        assertEquals(listOf("REC_00002.wav"), plan.downloads.map { it.name })
        assertEquals(listOf(SkippedFile("rec_3.WAV", SkipReason.ACTIVE)), plan.skipped)
    }

    @Test fun withTheSettingOffEverythingWaitsForTheRecordingAsBefore() {
        val plan = SyncPlanner.plan(recordingListing, namedRecording, RecorderSyncState(), emptyMap(), false)
        assertTrue(plan.downloads.isEmpty())
        assertFalse(plan.whileRecording)
        assertEquals(setOf(SkipReason.RECORDING, SkipReason.EMPTY), plan.skipped.map { it.reason }.toSet())
    }

    @Test fun olderFirmwareWithoutTheRecordingIndexKeepsSkippingEverything() {
        val plan = SyncPlanner.plan(recordingListing, recording, RecorderSyncState(), emptyMap(), true)
        assertTrue(plan.downloads.isEmpty())
        assertFalse(plan.whileRecording)
    }

    @Test fun aStereoRecordingOrAnUnnamedOneAllowsNoDownload() {
        val stereo = namedRecording.copy(paceMillis = 30_000)
        val none = RecorderSyncState()
        assertTrue(SyncPlanner.plan(recordingListing, stereo, none, emptyMap(), true).downloads.isEmpty())
        val unnamed = namedRecording.copy(recordingIndex = 0)
        assertTrue(SyncPlanner.plan(recordingListing, unnamed, none, emptyMap(), true).downloads.isEmpty())
        assertFalse(SyncPlanner.downloadsWhileRecording(namedRecording.copy(paceMillis = null)))
        // Not recording: the fields change nothing.
        val notRecording = idle.copy(recordingIndex = 0, paceMillis = 0)
        val plan = SyncPlanner.plan(recordingListing, notRecording, RecorderSyncState(), emptyMap(), true)
        assertEquals(4, plan.downloads.size)
        assertFalse(plan.whileRecording)
    }

    @Test fun resumesAStoppedDownloadOfTheSameFile() {
        val partial = PartialDownload("REC_0001.wav", 1_000, 640)
        val state = RecorderSyncState(partial = partial)
        fun resume(size: Long): PartialDownload? {
            val plan = SyncPlanner.plan(listOf(file("REC_0001.wav", size)), idle, state, emptyMap())
            return plan.downloads.single().resume
        }
        assertEquals(partial, resume(1_000))
        // Another size: start again.
        assertNull(resume(1_200))
    }

    @Test fun uploadsMissingOrStaleTranscripts() {
        val state = RecorderSyncState(
            listOf(
                SyncedRecording("REC_0001.wav", 1_000, "n1"), // never uploaded
                SyncedRecording("REC_0002.wav", 1_000, "n2", transcriptUploadedVersion = "v1"), // up to date
                SyncedRecording("REC_0003.wav", 1_000, "n3", transcriptUploadedVersion = "v1"), // newer transcript
                SyncedRecording("REC_0004.wav", 1_000, "n4", transcriptUploadedVersion = "v1"), // sidecar deleted
                SyncedRecording("REC_0005.wav", 1_000, "n5"), // not transcribed yet
                SyncedRecording("REC_0006.wav", 1_000, "n6"), // no longer on the recorder
                SyncedRecording("REC_0007.wav", 1_000, null), // import rejected
            ),
        )
        val listing = listOf(
            file("REC_0001.wav", 1_000),
            file("REC_0002.wav", 1_000),
            file("REC_0002.cwx", 500),
            file("REC_0003.wav", 1_000),
            file("REC_0003.cwx", 500),
            file("REC_0004.wav", 1_000),
            file("REC_0005.wav", 1_000),
            file("REC_0007.wav", 1_000),
        )
        val versions = mapOf("n1" to "v1", "n2" to "v1", "n3" to "v2", "n4" to "v1", "n6" to "v1")
        val plan = SyncPlanner.plan(listing, recording, state, versions)
        assertEquals(
            listOf("REC_0001.cwx" to "v1", "REC_0003.cwx" to "v2", "REC_0004.cwx" to "v1"),
            plan.uploads.map { it.sidecarName to it.transcriptVersion },
        )
        assertTrue(plan.downloads.isEmpty())
    }

    @Test fun aRefusedVersionIsNotSentAgainButANewOneIs() {
        val refused = SyncedRecording("REC_0001.wav", 1_000, "n1", transcriptRefusedVersion = "v1")
        val listing = listOf(file("REC_0001.wav", 1_000))
        val state = RecorderSyncState(listOf(refused))
        assertTrue(SyncPlanner.plan(listing, idle, state, mapOf("n1" to "v1")).uploads.isEmpty())
        val newer = SyncPlanner.plan(listing, idle, state, mapOf("n1" to "v2"))
        assertEquals(listOf("v2"), newer.uploads.map { it.transcriptVersion })
    }

    @Test fun aSidecarInAnotherCaseIsTheSameFile() {
        val sent = SyncedRecording("REC_0001.wav", 1_000, "n1", transcriptUploadedVersion = "v1")
        val state = RecorderSyncState(listOf(sent))
        val listing = listOf(file("REC_0001.wav", 1_000), file("rec_0001.CWX", 300))
        assertTrue(SyncPlanner.plan(listing, idle, state, mapOf("n1" to "v1")).uploads.isEmpty())
    }

    @Test fun aRecordingInAnotherCaseIsTheSameFile() {
        val known = SyncedRecording("REC_0001.wav", 1_000, "n1", headSha256 = "h1")
        val listing = listOf(file("rec_0001.WAV", 1_000))
        val state = RecorderSyncState(listOf(known))
        val plan = SyncPlanner.plan(listing, idle, state, mapOf("n1" to "v1"))
        assertTrue(plan.downloads.isEmpty())
        assertEquals(listOf("REC_0001.cwx"), plan.uploads.map { it.sidecarName })
        assertEquals(listOf(known), SyncPlanner.suspects(listing, state, mapOf("n1" to "v1")))

        val partial = PartialDownload("rec_0001.wav", 1_000, 640)
        val partialState = RecorderSyncState(partial = partial)
        val resumed = SyncPlanner.plan(listOf(file("REC_0001.WAV", 1_000)), idle, partialState, emptyMap())
        assertEquals(partial, resumed.downloads.single().resume)
    }

    @Test fun suspectsAreKnownFilesWithoutTheirSidecar() {
        val state = RecorderSyncState(
            listOf(
                SyncedRecording("REC_0001.wav", 1_000, "n1", "h1", transcriptUploadedVersion = "v1"), // sidecar gone
                SyncedRecording("REC_0002.wav", 1_000, "n2", "h2", transcriptUploadedVersion = "v1"), // sidecar there
                SyncedRecording("REC_0003.wav", 1_000, "n3", "h3"), // about to send a first one
                SyncedRecording("REC_0004.wav", 1_000, "n4", "h4"), // nothing to send
                SyncedRecording("REC_0005.wav", 1_000, "n5", null, transcriptUploadedVersion = "v1"), // no hash
                SyncedRecording("REC_0006.wav", 1_000, "n6", "h6", transcriptUploadedVersion = "v1"), // other size
                SyncedRecording("REC_0007.wav", 1_000, "n7", "h7", transcriptRefusedVersion = "v1"), // refused
            ),
        )
        val listing = listOf(
            file("REC_0001.wav", 1_000),
            file("REC_0002.wav", 1_000),
            file("REC_0002.cwx", 300),
            file("REC_0003.wav", 1_000),
            file("REC_0004.wav", 1_000),
            file("REC_0005.wav", 1_000),
            file("REC_0006.wav", 2_000),
            file("REC_0007.wav", 1_000),
        )
        val suspects = SyncPlanner.suspects(listing, state, mapOf("n3" to "v1", "n7" to "v1"))
        assertEquals(listOf("REC_0001.wav", "REC_0003.wav"), suspects.map { it.name })
    }

    @Test fun noUploadWhenTheSidecarNameIsTooLong() {
        val name = "a".repeat(40) + ".wav"
        val state = RecorderSyncState(listOf(SyncedRecording(name, 1_000, "n1")))
        val plan = SyncPlanner.plan(listOf(file(name, 1_000)), idle, state, mapOf("n1" to "v1"))
        assertTrue(plan.uploads.isEmpty())
        assertNull(plan.downloads.firstOrNull())
    }
}
