package org.cassini.core.library

import org.cassini.core.format.DocumentState
import org.cassini.core.library.NoteStatus.Suffix
import org.junit.Assert.assertEquals
import org.junit.Test

class NoteStatusTest {
    private fun status(
        kind: AudioKind,
        document: DocumentState? = null,
        job: JobState? = null,
        active: Boolean = false,
    ) = NoteStatus.of(NoteFacts(kind, document, job, active))

    @Test fun recordingOnlyForTheActiveCapture() {
        assertEquals(NoteStatus.RECORDING, status(AudioKind.LIVE_CAPTURE, active = true))
        assertEquals(NoteStatus.AUDIO_ONLY, status(AudioKind.LIVE_CAPTURE, active = false))
        // The active flag alone does not make a recording: only a live capture is one.
        assertEquals(NoteStatus.AUDIO_ONLY, status(AudioKind.PLAIN, active = true))
    }

    @Test fun jobRows() {
        assertEquals(NoteStatus.TRANSCRIBING, status(AudioKind.PLAIN, job = JobState.RUNNING))
        assertEquals(NoteStatus.QUEUED, status(AudioKind.PLAIN, job = JobState.QUEUED))
        assertEquals(NoteStatus.PAUSED, status(AudioKind.PLAIN, job = JobState.PAUSED))
        assertEquals(NoteStatus.FAILED, status(AudioKind.PLAIN, job = JobState.FAILED))
        assertEquals(NoteStatus.AUDIO_ONLY, status(AudioKind.PLAIN, job = JobState.NEEDS_CONSENT))
    }

    @Test fun audioAndDocumentRows() {
        assertEquals(NoteStatus.UNPLAYABLE, status(AudioKind.UNPLAYABLE))
        assertEquals(NoteStatus.UNREADABLE, status(AudioKind.DOCUMENT, DocumentState.UNREADABLE))
        assertEquals(NoteStatus.UNREADABLE, status(AudioKind.DOCUMENT, DocumentState.INVALID_METADATA))
        assertEquals(NoteStatus.NEWER_FORMAT, status(AudioKind.DOCUMENT, DocumentState.UNKNOWN_FORMAT))
        assertEquals(NoteStatus.TRANSCRIBED, status(AudioKind.DOCUMENT, DocumentState.OK))
        assertEquals(NoteStatus.TRANSCRIBED, status(AudioKind.DOCUMENT, DocumentState.UNVERIFIED))
        assertEquals(NoteStatus.TRANSCRIBED, status(AudioKind.DOCUMENT, DocumentState.STALE_AUDIO))
        assertEquals(NoteStatus.AUDIO_ONLY, status(AudioKind.DOCUMENT, DocumentState.PLAIN_AUDIO))
        assertEquals(NoteStatus.AUDIO_ONLY, status(AudioKind.PLAIN))
    }

    @Test fun firstMatchWins() {
        // An active capture beats any job row.
        assertEquals(NoteStatus.RECORDING, status(AudioKind.LIVE_CAPTURE, job = JobState.RUNNING, active = true))
        // A job beats an unplayable file and every document state.
        assertEquals(NoteStatus.TRANSCRIBING, status(AudioKind.UNPLAYABLE, job = JobState.RUNNING))
        assertEquals(NoteStatus.QUEUED, status(AudioKind.DOCUMENT, DocumentState.OK, JobState.QUEUED))
        assertEquals(NoteStatus.PAUSED, status(AudioKind.DOCUMENT, DocumentState.UNREADABLE, JobState.PAUSED))
        assertEquals(NoteStatus.FAILED, status(AudioKind.DOCUMENT, DocumentState.UNKNOWN_FORMAT, JobState.FAILED))
        // Unplayable beats the document state.
        assertEquals(NoteStatus.UNPLAYABLE, status(AudioKind.UNPLAYABLE, DocumentState.OK))
        // Unreadable beats newer format and transcribed; a consent wait does not hide a transcript.
        assertEquals(NoteStatus.TRANSCRIBED, status(AudioKind.DOCUMENT, DocumentState.OK, JobState.NEEDS_CONSENT))
        val invalid = status(AudioKind.DOCUMENT, DocumentState.INVALID_METADATA, JobState.NEEDS_CONSENT)
        assertEquals(NoteStatus.UNREADABLE, invalid)
    }

    @Test fun everyCombinationHasAStatus() {
        val documents = listOf<DocumentState?>(null) + DocumentState.entries
        val jobs = listOf<JobState?>(null) + JobState.entries
        val all = AudioKind.entries.flatMap { kind ->
            documents.flatMap { document ->
                jobs.flatMap { job -> listOf(true, false).map { status(kind, document, job, it) } }
            }
        }
        assertEquals(AudioKind.entries.size * documents.size * jobs.size * 2, all.size)
        assertEquals(NoteStatus.entries.toSet(), all.toSet())
    }

    @Test fun suffixes() {
        assertEquals(Suffix.RESUME, NoteStatus.suffix(NoteStatus.PAUSED, 0, 0))
        assertEquals(Suffix.TRY_AGAIN, NoteStatus.suffix(NoteStatus.FAILED, 0, 0))
        assertEquals(Suffix.SPEAKERS, NoteStatus.suffix(NoteStatus.TRANSCRIBED, 3, 120))
        assertEquals(Suffix.SPEAKERS, NoteStatus.suffix(NoteStatus.TRANSCRIBED, 2, 1))
        assertEquals(Suffix.NONE, NoteStatus.suffix(NoteStatus.TRANSCRIBED, 1, 120))
        assertEquals(Suffix.NO_SPEECH, NoteStatus.suffix(NoteStatus.TRANSCRIBED, 0, 0))
        assertEquals(Suffix.NONE, NoteStatus.suffix(NoteStatus.AUDIO_ONLY, 0, 0))
        assertEquals(Suffix.NONE, NoteStatus.suffix(NoteStatus.TRANSCRIBING, 3, 10))
    }
}
