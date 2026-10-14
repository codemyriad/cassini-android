package org.cassini.data.checkpoint

import org.cassini.core.checkpoint.CheckpointNames
import org.cassini.core.checkpoint.CheckpointState
import org.cassini.core.format.Word
import org.cassini.core.models.ModelCatalog
import java.io.File

// The values the checkpoint repository takes and returns (docs/architecture.md, sections 3.5, 3.6 and 4.4).

/**
 * What a checkpoint must match to be resumed, built from the note row: [source] is the note's audio file name
 * (`capture.opus`, `source.mp3`, `document.opus`, …) and [signature] its `length:mtime`; the model values are the
 * pinned ones of this build. The repository adds the on-disk lengths of `clips.pcm` and `audio.opus.part` itself,
 * under the note's lock, when it builds [CheckpointState.Expected].
 */
data class CheckpointIdentity(
    val source: String,
    val signature: String,
    val modelRevision: String = ModelCatalog.PARAKEET_REVISION,
    val speakersSha256: String = ModelCatalog.diarizationModel.sha256,
) {
    companion object {
        /** The identity of a job over [audioFile] in [noteDir], with this build's pinned models. */
        fun of(noteDir: File, audioFile: String): CheckpointIdentity =
            CheckpointIdentity(audioFile, CheckpointState.signature(File(noteDir, audioFile)))
    }
}

/** Why a saved checkpoint cannot be resumed ("Saved progress no longer matches"). */
enum class MismatchReason {
    /** `checkpoint.json` is not JSON, or a member is missing or has the wrong type. */
    UNREADABLE,

    /** A `version` this build does not know. */
    VERSION,

    /** The checkpoint was made for another audio file of the note. */
    SOURCE,

    /** The audio file changed (length or modification time). */
    SIGNATURE,

    /** Another speech model or revision, for example a checkpoint of Parakeet TDT 0.6B v3 in this build. */
    MODEL,

    /** Another diarization model. */
    SPEAKERS,

    /** `clips.pcm` is shorter than the saved clip count, or the count is out of range. */
    CLIPS,

    /** `audio.opus.part` is shorter than the saved encoder length. */
    AUDIO,

    /** A position or done time out of range. */
    RANGE,

    /** `words.log` is shorter than the saved prefix, or its prefix does not hold the saved words. */
    WORDS,
}

/** The result of loading a checkpoint. */
sealed interface CheckpointLoad {
    /** The checkpoint matches; the job resumes from [state]. */
    data class Valid(val state: CheckpointState) : CheckpointLoad

    /** No `checkpoint.json` (nor its backup). */
    data object Missing : CheckpointLoad

    /** A checkpoint exists but fails `CheckpointState.validate`; [detail] is a diagnostic, never shown as is. */
    data class Mismatch(val reason: MismatchReason, val detail: String? = null) : CheckpointLoad
}

/** What `work/` of a note holds, for the Note and Library screens. */
sealed interface CheckpointStatus {
    /** No checkpoint and no sealed output. */
    data object None : CheckpointStatus

    /** A checkpoint that can be resumed: [doneMs] of [durationMs] are transcribed, with [words] words. */
    data class Saved(val doneMs: Long, val durationMs: Long, val words: Int) : CheckpointStatus

    /** A checkpoint that cannot be resumed. */
    data class Mismatch(val reason: MismatchReason) : CheckpointStatus

    /** A finished `work/sealed.opus` with its marker waits for adopt. */
    data object SealedAwaitingAdopt : CheckpointStatus

    data object NoNote : CheckpointStatus
}

/**
 * The words of a paused note's saved chunks for the draft view: [words] carry the speaker ids with the draft merges
 * resolved, [labels] maps a resolved speaker id to its draft name (the user's label, else the automatic name of its
 * AUTO match); speakers without an entry show their placeholder. [names] is the draft state they came from.
 */
data class DraftTranscript(
    val words: List<Word>,
    val labels: Map<String, String>,
    val names: CheckpointNames,
    val doneMs: Long,
    val durationMs: Long,
)
