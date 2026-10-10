package org.cassini.data.voices

import org.cassini.core.Failure
import org.cassini.core.speakers.Voice

/** One saved person as Saved voices lists it (features.md section 10). */
data class SavedVoice(
    val voice: Voice,
    /** Live notes whose selected transcript links a speaker to this person, automatically or confirmed. */
    val notes: Int,
    /** "Name them again in a note to keep recognising them" (`VoiceStaleness`). */
    val stale: Boolean,
) {
    val id: String get() = voice.id
    val name: String get() = voice.name

    /** Seconds of confirmed speech enrolled into this person. */
    val confirmedSeconds: Double get() = voice.seconds
}

/**
 * The Saved voices list, sorted by notes, then confirmed seconds, then name. [problem] is set when `people.json`
 * could not be used as it is; [readOnly] means it stays untouched and every edit is refused ("Saved voices can't be
 * read", features.md section 10). [canForgetEveryone] is true for a damaged list: "Forget everyone" starts it again.
 */
data class SavedVoices(val voices: List<SavedVoice>, val problem: VoiceProblem?) {
    val readOnly: Boolean get() = problem != null
    val canForgetEveryone: Boolean get() = problem == null || problem == VoiceProblem.DAMAGED
}

/** The result of an edit of the saved people. */
sealed interface VoiceEdit {
    data object Done : VoiceEdit

    data object NotFound : VoiceEdit

    /** `people.json` is from a newer Cassini, cannot be read or is damaged; nothing was changed. */
    data object ReadOnly : VoiceEdit

    /** The name is blank or longer than [VoiceRepository.MAX_NAME_LENGTH] after trimming. */
    data object Invalid : VoiceEdit

    /** A voice file could not be written (full disk, I/O error); try again. */
    data class Failed(val failure: Failure) : VoiceEdit
}

/** The result of `adoptPending`. */
sealed interface PendingOutcome {
    /**
     * The pending prints are in `voices/notes/<id>.json` now. [droppedMatches] matches named voices that no longer
     * exist; [enrolled] lists the voice ids that the draft's Remember choices enrolled.
     */
    data class Merged(val prints: Int, val droppedMatches: Int, val enrolled: List<String>) : PendingOutcome

    /** "Forget everyone" ran after the job matched: no print and no match was kept. */
    data object Discarded : PendingOutcome

    /** No pending file (also after a completed adopt). */
    data object NothingPending : PendingOutcome

    /** The note has no row any more; the pending file was deleted. */
    data object NoNote : PendingOutcome

    /**
     * A voice file of the note is newer or cannot be read, or `people.json` or `generation` cannot be read now; the
     * files stay as they are and the pending file is kept for a retry.
     */
    data class Kept(val problem: VoiceProblem) : PendingOutcome
}

/** The result of `unenrol`. */
sealed interface UnenrolOutcome {
    /** One enrolment was taken back; the person is still saved. */
    data object Unenrolled : UnenrolOutcome

    /** The person had only this print and was forgotten everywhere. */
    data object Forgotten : UnenrolOutcome

    /** There was no confirmed match to take back. */
    data object NothingToUndo : UnenrolOutcome

    data object ReadOnly : UnenrolOutcome

    /** A voice file could not be written (full disk, I/O error); try again. */
    data class Failed(val failure: Failure) : UnenrolOutcome
}
