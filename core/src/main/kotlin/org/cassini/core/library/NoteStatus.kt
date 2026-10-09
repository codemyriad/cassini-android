package org.cassini.core.library

import org.cassini.core.format.DocumentState

/**
 * What the Library status chip needs to know about one note: [audioKind], [documentState] (null unless the audio is a
 * document), [jobState] (null without a job row) and whether the note is the capture being recorded now.
 */
data class NoteFacts(
    val audioKind: AudioKind,
    val documentState: DocumentState? = null,
    val jobState: JobState? = null,
    val isActiveCapture: Boolean = false,
)

/** The Library status chip (features.md section 1). [of] applies the table rows in order; the first match wins. */
enum class NoteStatus {
    RECORDING,
    TRANSCRIBING,
    QUEUED,
    PAUSED,
    FAILED,
    UNPLAYABLE,
    UNREADABLE,
    NEWER_FORMAT,
    TRANSCRIBED,
    AUDIO_ONLY,
    ;

    /** The text after the chip on the meta line. */
    enum class Suffix { NONE, RESUME, TRY_AGAIN, SPEAKERS, NO_SPEECH }

    companion object {
        fun of(facts: NoteFacts): NoteStatus = when {
            facts.audioKind == AudioKind.LIVE_CAPTURE && facts.isActiveCapture -> RECORDING
            facts.jobState == JobState.RUNNING -> TRANSCRIBING
            facts.jobState == JobState.QUEUED -> QUEUED
            facts.jobState == JobState.PAUSED -> PAUSED
            facts.jobState == JobState.FAILED -> FAILED
            facts.audioKind == AudioKind.UNPLAYABLE -> UNPLAYABLE
            else -> ofDocument(facts.documentState)
        }

        private fun ofDocument(state: DocumentState?): NoteStatus = when (state) {
            DocumentState.UNREADABLE, DocumentState.INVALID_METADATA -> UNREADABLE
            DocumentState.UNKNOWN_FORMAT -> NEWER_FORMAT
            DocumentState.OK, DocumentState.UNVERIFIED, DocumentState.STALE_AUDIO -> TRANSCRIBED
            DocumentState.PLAIN_AUDIO, null -> AUDIO_ONLY
        }

        /**
         * The meta-line suffix: "· Resume" when paused, "· Try again" when failed, and for a transcribed note
         * "· No speech" at 0 words or "· N speakers" with [speakerCount] of 2 or more.
         */
        fun suffix(status: NoteStatus, speakerCount: Int, wordCount: Int): Suffix = when (status) {
            PAUSED -> Suffix.RESUME
            FAILED -> Suffix.TRY_AGAIN
            TRANSCRIBED -> when {
                wordCount == 0 -> Suffix.NO_SPEECH
                speakerCount >= 2 -> Suffix.SPEAKERS
                else -> Suffix.NONE
            }
            else -> Suffix.NONE
        }
    }
}
