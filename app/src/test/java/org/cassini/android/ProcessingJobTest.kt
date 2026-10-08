package org.cassini.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProcessingJobTest {
    @Test fun persistedFieldsRoundTripAndLiveWordsDoNot() {
        val job = ProcessingJob("note-1", "Meeting.opus", speakers = true, phase = ProcessingJob.Phase.ASR, doneMs = 600_000,
            totalMs = 3_300_000, elapsedMs = 70_000, audioMs = 600_000, stageMs = 60_000, failure = Failure.STALLED,
            settledWords = 1234, attempts = 2, words = listOf(Word("spk_1", 0, 100, "ciao")))
        val back = ProcessingJob.fromJson(JSONObject(job.json().toString()))
        assertEquals(job.copy(words = emptyList()), back)
    }

    @Test fun anotherVersionIsNotRead() {
        assertNull(ProcessingJob.fromJson(JSONObject().put("v", 2).put("noteId", "x").put("phase", "ASR")))
    }

    @Test fun speedIsAudioOverWallTimeAndTimeLeftFollowsIt() {
        val job = ProcessingJob("n", phase = ProcessingJob.Phase.ASR, doneMs = 600_000, totalMs = 3_300_000, audioMs = 600_000, stageMs = 60_000)
        assertEquals(10.0, job.speed!!, 1e-9)
        assertEquals(18, job.percent)
        assertEquals(270_000L, job.remainingMs)
        assertNull(ProcessingJob("n").speed)
    }

    @Test fun onlyWorkingPhasesAreActive() {
        val active = ProcessingJob.Phase.entries.filter { it.active }.toSet()
        assertEquals(setOf(ProcessingJob.Phase.QUEUED, ProcessingJob.Phase.DECODE, ProcessingJob.Phase.ASR,
            ProcessingJob.Phase.DIARIZE, ProcessingJob.Phase.PACKAGE), active)
    }

    @Test fun aPartialNoteKeepsItsWordsAndFlag() {
        val session = Session(uri = "file:///a.opus", name = "a", transcript = Transcript(listOf(Word("spk_1", 0, 10, "uno"))),
            document = "/docs/old.opus", partial = true, libraryId = "id")
        val note = LibraryNote.update(null, session, "id", 1)
        val back = LibraryNote.fromJson(JSONObject(note.json().toString()))
        assertTrue(back.session.partial)
        assertEquals(session.transcript, back.session.transcript)
        assertNull(LibraryNote.fromJson(JSONObject(LibraryNote.update(null, session.copy(partial = false), "id", 1).json().toString())).session.transcript)
    }
}
