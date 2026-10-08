package org.cassini.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProcessingJobTest {
    @Test fun persistedFieldsRoundTripAndLiveWordsDoNot() {
        val job = ProcessingJob("note-1", "Meeting.opus", speakers = true, phase = ProcessingJob.Phase.ASR, doneMs = 600_000,
            totalMs = 3_300_000, elapsedMs = 70_000, audioMs = 600_000, stageMs = 60_000, failure = Failure.STALLED,
            settledWords = 1234, attempts = 2, pending = listOf(Word("spk_1", 0, 100, "ciao")))
        val back = ProcessingJob.fromJson(JSONObject(job.json().toString()))
        assertEquals(job.copy(pending = emptyList()), back)
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

    @Test fun settledWordsHandOutOnlyWhatFollows() {
        val words = ProcessingJobs.beginWords(listOf(Word("spk_1", 0, 1, "a")))
        assertSame(words, ProcessingJobs.settled)
        words.addAll(listOf(Word("spk_1", 1, 2, "b"), Word("spk_1", 2, 3, "c")))
        assertEquals(listOf("b", "c"), words.since(1).map { it.text })
        assertEquals(emptyList<Word>(), words.since(9))
        assertEquals(3, words.size)
    }
}
