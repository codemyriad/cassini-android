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
        assertEquals(listOf(PlannedDownload("REC_0001.wav", 2_000, 0, old)), plan.downloads)
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

    @Test fun resumesAStoppedDownloadOfTheSameFile() {
        val state = RecorderSyncState(partial = PartialDownload("REC_0001.wav", 1_000, 640))
        fun resumeOffset(size: Long): Long {
            val plan = SyncPlanner.plan(listOf(file("REC_0001.wav", size)), idle, state, emptyMap())
            return plan.downloads.single().resumeOffset
        }
        assertEquals(640L, resumeOffset(1_000))
        // Another size: start again.
        assertEquals(0L, resumeOffset(1_200))
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

    @Test fun noUploadWhenTheSidecarNameIsTooLong() {
        val name = "a".repeat(40) + ".wav"
        val state = RecorderSyncState(listOf(SyncedRecording(name, 1_000, "n1")))
        val plan = SyncPlanner.plan(listOf(file(name, 1_000)), idle, state, mapOf("n1" to "v1"))
        assertTrue(plan.uploads.isEmpty())
        assertNull(plan.downloads.firstOrNull())
    }
}
