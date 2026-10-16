package org.cassini.engine.job

import org.cassini.core.Failure
import org.cassini.core.asr.ProgressAnchor
import org.cassini.core.asr.TranscriptionProgress
import org.cassini.core.format.DocumentState
import org.cassini.core.format.Word
import org.cassini.core.library.PauseReason
import org.cassini.core.models.ModelPart

// The value types a transcription job and its progress tracker share with the coordinator and the ViewModels
// (architecture sections 5.4 and 5.5). They are immutable: data classes over read-only collections, and objects.

/** What the Note screen and the notification show for a note whose job runs. */
sealed interface JobPhase {
    /** The job was claimed and is loading its checkpoint, voices and names ("Starting…"). */
    data object Starting : JobPhase

    /** A cancelled job still holds [NativeBudget] ("Finishing the previous note…"). */
    data object WaitingForPrevious : JobPhase

    /** Models are being installed; [progress] is null until the first byte or check is reported. */
    data class Downloading(val progress: DownloadProgress?) : JobPhase

    /** The pipeline runs: the progress card and the draft transcript. */
    data class Transcribing(val progress: RunningProgress, val draft: DraftState) : JobPhase

    /**
     * Stop was asked. During the decode the job saves a checkpoint at its next sample block, if at least one window
     * is done; after the decode the final checkpoint stays and the job ends at its next check.
     */
    data object Stopping : JobPhase
}

/**
 * One part of a model install: [doneBytes] of [totalBytes] of [part]'s missing models. While [verifying], the count
 * is the share of the files already checked ("Checking files — 80%"); otherwise the bytes downloaded.
 */
data class DownloadProgress(val part: ModelPart, val doneBytes: Long, val totalBytes: Long, val verifying: Boolean) {
    /** Whole percent of [totalBytes], 0 to 100. */
    val percent: Int
        get() = if (totalBytes <= 0) 0 else (doneBytes.coerceIn(0, totalBytes) * PERCENT / totalBytes).toInt()

    private companion object {
        const val PERCENT = 100L
    }
}

/**
 * Why a model install failed, as the download banners tell it (features.md section 13.4). Persisted in `jobs.failure`
 * as [JobFailure.DOWNLOAD_PREFIX] plus the name (a core `Failure` has names such as INSTALL too): never rename.
 */
enum class DownloadError {
    /** No validated network, or the connection timed out ("No connection"). */
    NO_CONNECTION,

    /** The transfer failed with a network: an HTTP status, an early end ("Download stopped"). */
    INTERRUPTED,

    /** The free-space check failed ("Not enough storage"). */
    NOT_ENOUGH_STORAGE,

    /** Size or SHA-256 did not match; the damaged file was deleted ("File check failed"). */
    FILE_CHECK_FAILED,

    /** A verified file could not be put in place ("Couldn't finish installing"). */
    INSTALL,

    /** A redirect pointed outside the download hosts; shown as "Download stopped". */
    HOST_NOT_ALLOWED,

    /** Android stopped the download service (a time-out); "Download stopped" with its own body. */
    STOPPED_BY_SYSTEM,
}

/** The stepper of the progress card, in order. [ordinal] is the step index (0 to 4). */
enum class Step {
    READING,
    PREPARING,
    WORDS_AND_VOICES,
    MATCHING,
    SAVING,
    ;

    companion object {
        /** The step a core [TranscriptionProgress.Stage] belongs to: WORDS and SPEAKERS are one step. */
        fun of(stage: TranscriptionProgress.Stage): Step = when (stage) {
            TranscriptionProgress.Stage.READING -> READING
            TranscriptionProgress.Stage.PREPARING -> PREPARING
            TranscriptionProgress.Stage.WORDS, TranscriptionProgress.Stage.SPEAKERS -> WORDS_AND_VOICES
            TranscriptionProgress.Stage.MATCHING -> MATCHING
            TranscriptionProgress.Stage.SAVING -> SAVING
        }
    }
}

/** The progress card's headline (ui.md section 7; the copy is in features.md section 3.1). */
enum class ProgressHeadline {
    STARTING,
    WAITING_FOR_PREVIOUS,
    READING_AUDIO,
    PREPARING,
    FINDING_WORDS_AND_SPEAKERS,

    /** The WORDS stage when no diarizer is installed. */
    FINDING_WORDS,
    MATCHING_VOICES,
    SAVING,
}

/** The "Time left" cell. */
sealed interface TimeLeft {
    /** Fewer than 3 windows since the (re)start, or the total is unknown. */
    data object Calculating : TimeLeft

    /** Nothing left to decode; only finalization remains. */
    data object Finishing : TimeLeft

    /** Rounded with `TranscriptionProgress.roundedRemaining`, finalization included. */
    data class Range(val lowMs: Long, val highMs: Long) : TimeLeft
}

/** The "Battery at finish" cell. */
sealed interface BatteryLine {
    /** No battery reading: the cell is hidden. */
    data object Unavailable : BatteryLine

    /** Not enough discharge measured yet (under 120 s or under 0.5 %). */
    data class Measuring(val percentNow: Int) : BatteryLine

    data class PluggedIn(val percentNow: Int) : BatteryLine

    data class Projected(val percentNow: Int, val percentAtFinish: Int) : BatteryLine
}

/**
 * The numbers of the progress card at one tick. [percent] is null for an indeterminate bar and at most 99 until the
 * job is done. [rangeStartMs]..[rangeEndMs] is the window being worked on (hidden when both are 0).
 * [matchingSkipped] draws the Matching step as skipped: the job will not match voices in this run.
 * [anchor] lets the bar move between window completions; null outside the Words & voices step.
 */
data class RunningProgress(
    val headline: ProgressHeadline,
    val rangeStartMs: Long,
    val rangeEndMs: Long,
    val percent: Int?,
    val step: Step,
    val matchingSkipped: Boolean,
    val timeLeft: TimeLeft,
    val doneMs: Long,
    val totalMs: Long,
    val battery: BatteryLine,
    val words: Int,
    val lowMemory: Boolean,
    val anchor: ProgressAnchor? = null,
)

/**
 * An inline "Is this Luca?" card: [speakerId] (as shown, after merges) has a live SUGGESTED match to the saved voice
 * [voiceId] called [name] with [score]; the card sits at that speaker's most recent turn, which starts at [atMs].
 */
data class VoiceSuggestion(
    val speakerId: String,
    val voiceId: String,
    val name: String,
    val score: Float,
    val atMs: Long,
)

/**
 * The draft transcript while a job runs: [words] with draft merges applied, the shown [labels] per speaker (draft name,
 * then AUTO match, then the placeholder), the speakers whose label comes from an AUTO match ([automatic], drawn with
 * the "auto" badge), [suggestions], and [tentativeFromMs]: words ending after it are drawn as unsettled.
 * [namingOpen] is false from the attribute step on ("Saving — you can rename when it's done").
 */
data class DraftState(
    val words: List<Word>,
    val labels: Map<String, String>,
    val automatic: Set<String>,
    val suggestions: List<VoiceSuggestion>,
    val tentativeFromMs: Long,
    val namingOpen: Boolean,
) {
    companion object {
        val EMPTY = DraftState(emptyList(), emptyMap(), emptySet(), emptyList(), 0, namingOpen = true)
    }
}

/**
 * Why a job failed. [code] is what `jobs.failure` stores: a core `Failure` name, [DOWNLOAD_PREFIX] plus a
 * [DownloadError] name, [DOCUMENT_PREFIX] plus a [DocumentState] name, or `CHECKPOINT_MISMATCH`. No two failures
 * share a code.
 */
sealed interface JobFailure {
    val code: String

    /** A pipeline failure with its stable core code. */
    data class Processing(val failure: Failure) : JobFailure {
        override val code: String get() = failure.name
    }

    /**
     * The required speech model could not be installed. [requiredFreeBytes] is the figure the installer's space check
     * used: DL_NO_SPACE shows "Free about N MB" from it.
     */
    data class Download(val error: DownloadError, val part: ModelPart, val requiredFreeBytes: Long) : JobFailure {
        override val code: String get() = DOWNLOAD_PREFIX + error.name
    }

    /** The saved checkpoint no longer matches the audio or the models ("Saved progress no longer matches"). */
    data object CheckpointMismatch : JobFailure {
        override val code: String get() = "CHECKPOINT_MISMATCH"
    }

    /**
     * Transcribe again was refused before any work: the note's document is in [state], so it can not take a new
     * version, and features.md section 6 does not let a fresh document replace it (only STALE_AUDIO, UNREADABLE and
     * INVALID_METADATA). Seen for UNVERIFIED and UNKNOWN_FORMAT.
     */
    data class DocumentRefused(val state: DocumentState) : JobFailure {
        override val code: String get() = DOCUMENT_PREFIX + state.name
    }

    companion object {
        /** The start of every [Download] code, so "DOWNLOAD_INSTALL" never reads as core `Failure.INSTALL`. */
        const val DOWNLOAD_PREFIX = "DOWNLOAD_"

        /** The start of every [DocumentRefused] code. */
        const val DOCUMENT_PREFIX = "DOCUMENT_"

        /** The [DownloadError] of a stored [code], or null when the code is not a download failure. */
        fun downloadError(code: String): DownloadError? = code.takeIf { it.startsWith(DOWNLOAD_PREFIX) }
            ?.let { name -> DownloadError.entries.firstOrNull { DOWNLOAD_PREFIX + it.name == name } }
    }
}

/** How one run of a job ended. */
sealed interface JobOutcome {
    /** The document was sealed and adopted. */
    data object Done : JobOutcome

    /**
     * The document is sealed and marked (`work/sealed`), but adopt failed. The transcript is complete: the coordinator
     * finishes adopt as `reconcileOnStart` does (architecture section 5.3) and never shows the note as FAILED.
     */
    data object AdoptPending : JobOutcome

    /** The checkpoint is saved (when there was progress to save); the row becomes PAUSED([reason]). */
    data class Paused(val reason: PauseReason) : JobOutcome

    data class Failed(val failure: JobFailure) : JobOutcome

    /** No consent to download the [missingBytes] the job needs; no network call was made. */
    data class NeedsConsent(val missingBytes: Long) : JobOutcome

    /** Interrupted for Start over or trash; the coordinator discards the work directory after `join()`. */
    data object Cancelled : JobOutcome
}

/**
 * What a job tells its `ProgressTracker`, in order, over an unlimited channel (`trySend` from the inference thread).
 * Times are on the recording clock unless named `elapsed`.
 */
sealed interface JobEvent {
    /** A cancelled job still holds [NativeBudget]. */
    data object WaitingForPrevious : JobEvent

    data class Downloading(val progress: DownloadProgress?) : JobEvent

    /**
     * Inference begins. [totalMs] may be 0 (unknown). [restored] is set on a resume. [speakers]: a diarizer is
     * installed and has not failed; [prints]: the voiceprint model is installed. [matching]: the Matching step can
     * still run in this run (prints, and a diarizer installed or restored from the checkpoint); [NamingClosed] gives
     * the final answer. [lowMemory]: the memory preflight flagged the device.
     */
    data class Started(
        val totalMs: Long,
        val restored: Restored?,
        val speakers: Boolean,
        val prints: Boolean,
        val lowMemory: Boolean,
        val matching: Boolean = prints && speakers,
    ) : JobEvent

    /**
     * The checkpoint a resumed run starts from: done and total audio, the draft [words] it holds (raw speaker ids, no
     * merges), and its elapsed time.
     */
    data class Restored(val doneMs: Long, val totalMs: Long, val words: List<Word>, val elapsedMs: Long)

    /** The pipeline entered [stage] for the window [startMs]..[endMs] (0..0 when it has none). */
    data class StageChanged(
        val stage: TranscriptionProgress.Stage,
        val startMs: Long = 0,
        val endMs: Long = 0,
    ) : JobEvent

    /** The decoder has read [readMs] of the audio; [totalMs] once the container tells it. */
    data class Reading(val readMs: Long, val totalMs: Long?) : JobEvent

    /** A window was decoded: [doneMs] of [totalMs] and every draft word so far, with speakers. */
    data class WindowDone(val doneMs: Long, val totalMs: Long, val words: List<Word>) : JobEvent

    /** A checkpoint covering [doneMs] was saved. */
    data class Checkpointed(val doneMs: Long) : JobEvent

    /**
     * Speakers could not be told apart (Failure.SPEAKERS): every word is one voice from now on. The windows before
     * keep their speakers, so the Matching step can still run.
     */
    data object SpeakersUnavailable : JobEvent

    /** Step 6 closed naming. [matching]: the Matching step runs next; when false, it is skipped. */
    data class NamingClosed(val matching: Boolean) : JobEvent

    data object Stopping : JobEvent

    data class Ended(val outcome: JobOutcome) : JobEvent
}
