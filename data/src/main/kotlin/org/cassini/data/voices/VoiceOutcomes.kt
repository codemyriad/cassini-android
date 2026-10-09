package org.cassini.data.voices

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
 * could not be used as it is; [readOnly] means it stays untouched and every edit is refused.
 */
data class SavedVoices(val voices: List<SavedVoice>, val problem: VoiceProblem?) {
    val readOnly: Boolean get() = problem == VoiceProblem.NEWER || problem == VoiceProblem.UNREADABLE
}

/** The result of an edit of the saved people. */
sealed interface VoiceEdit {
    data object Done : VoiceEdit

    data object NotFound : VoiceEdit

    /** `people.json` is from a newer Cassini or cannot be read; nothing was changed. */
    data object ReadOnly : VoiceEdit

    /** The name is blank or longer than [VoiceRepository.MAX_NAME_LENGTH] after trimming. */
    data object Invalid : VoiceEdit
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

    /** A voice file of the note is newer or cannot be read; it stays as it is and the pending file is kept. */
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
}
