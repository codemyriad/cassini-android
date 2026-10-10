package org.cassini.ui.note

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import org.cassini.ui.banner.BannerSpec
import org.cassini.ui.components.ProcessingCardUi
import org.cassini.ui.components.SnackbarSpec
import org.cassini.ui.components.TurnUi
import org.cassini.ui.components.WordRef
import org.cassini.ui.format.DayLabel
import java.time.LocalTime

/**
 * The Note screen (boards 02 and 03, features.md section 3; ui.md section 7). One destination with modes.
 *
 * - [subtitle] is "Today, 10:02 · 48:13"; null in Reading mode (board 03 has none).
 * - [readOnlyReason] is set while the sealed transcript cannot be renamed, merged or switched.
 * - [chips], [turns], [activeWord], [playback] and [search] belong to the transcript; [banners] come before it
 *   (trust, failure, pause and download banners).
 * - [sheet] and [dialog] are the open overlay; [notificationsOff] switches the progress footnote.
 * - [snackbar] ("Saved “<file>”", "Couldn't save the file") is an addition to ui.md section 7: exports finish on
 *   this screen.
 */
@Immutable
data class NoteUiState(
    val noteId: String,
    val title: String,
    val subtitle: NoteSubtitleUi? = null,
    val mode: NoteMode,
    val readOnlyReason: ReadOnlyReason? = null,
    val chips: NoteChipsUi? = null,
    val banners: ImmutableList<BannerSpec> = persistentListOf(),
    val turns: ImmutableList<TurnUi> = persistentListOf(),
    val activeWord: WordRef? = null,
    val playback: PlaybackUi? = null,
    val search: TranscriptSearchUi? = null,
    val sheet: NoteSheet? = null,
    val dialog: NoteDialog? = null,
    val notificationsOff: Boolean = false,
    val snackbar: SnackbarSpec? = null,
)

/** The top bar subtitle: "<day>, <time> · <duration>". */
@Immutable
data class NoteSubtitleUi(val day: DayLabel, val time: LocalTime, val durationMs: Long)

/** The mode of the Note screen (features.md section 3, table). */
sealed interface NoteMode {
    data object Loading : NoteMode

    /** A plain note or one without consent: the PLAIN_AUDIO banner and the player. */
    data object AudioOnly : NoteMode

    /** A queued job: the Queued card ([ahead] notes before it); over a document, the Read & listen content follows. */
    @Immutable
    data class Queued(val ahead: Int, val overDocument: Boolean) : NoteMode

    /** A running job: the progress card and the draft. No player. */
    @Immutable
    data class Processing(val card: ProcessingCardUi) : NoteMode

    /** A paused job at [doneMs] of [totalMs]: the pause banner, the read-only draft and the player. */
    @Immutable
    data class Paused(val doneMs: Long, val totalMs: Long, val hasCheckpoint: Boolean) : NoteMode

    /** A failed job: the failure banner; over a document, the Read & listen content follows. */
    @Immutable
    data class Failed(val overDocument: Boolean) : NoteMode

    /** A document and no job: board 03. */
    data object Reading : NoteMode

    /** The audio cannot play: the UNPLAYABLE banner, no player. */
    data object Unplayable : NoteMode
}

/** Why the sealed transcript is read-only, and the message a rename or merge attempt shows. */
enum class ReadOnlyReason {
    /** "Wait until transcription finishes" (a job row exists for a document note). */
    JOB_PENDING,

    /** "Fix the transcript first: Transcribe again" (trust other than OK). */
    TRUST,

    /**
     * "Saving — you can rename when it's done" (the job reached the attribute step; draft naming is closed). An
     * addition to ui.md section 7 (features.md section 3.1).
     */
    SAVING,
}

/** The chips row of Read & listen: trust chip, version chip (2 or more readable variants) and the speaker count. */
@Immutable
data class NoteChipsUi(val trust: TrustUi, val version: VersionChipUi? = null, val speakers: Int)

/** "Version [index] of [count]" plus the suffix of [kind]. */
@Immutable
data class VersionChipUi(val index: Int, val count: Int, val kind: VersionKindUi)

/** The player: [positionMs] of [durationMs]; [following] is false while auto-follow is paused (the Follow chip shows). */
@Immutable
data class PlaybackUi(val positionMs: Long, val durationMs: Long, val playing: Boolean, val following: Boolean = true)

/**
 * In-note search: the [query] and its [matches] in text order; [current] indexes [matches] ("2 of 7" shows
 * `current + 1` of [total]; 0 matches show "No matches"). [matches] is an addition to ui.md section 7, which gives
 * only (query, current, total): the screen needs the ranges to draw the hits.
 *
 * [query] is the text of a field. The field keeps its own copy and sends each change as [NoteEvent.SearchQuery]; it
 * takes a different text from the state only when that text is not one it sent (SyncedText). Thus the
 * ViewModel can update this value through its StateFlow.
 */
@Immutable
data class TranscriptSearchUi(
    val query: String,
    val matches: ImmutableList<TranscriptMatch> = persistentListOf(),
    val current: Int = 0,
) {
    val total: Int get() = matches.size
}

/** One search hit: a character [range] of the paragraph text (`turnText`) of turn [turnId]. */
@Immutable
data class TranscriptMatch(val turnId: String, val range: IntRange)

/** The open sheet of the Note screen. [Info] is drawn as a dialog (ui.md section 8). */
sealed interface NoteSheet {
    @Immutable
    data class Actions(val ui: NoteActionsUi) : NoteSheet

    @Immutable
    data class NameSpeaker(val ui: NameSheetUi) : NoteSheet

    @Immutable
    data class Merge(val ui: MergeUi) : NoteSheet

    @Immutable
    data class Versions(val ui: VersionsUi) : NoteSheet

    data object SubtitleFormat : NoteSheet

    @Immutable
    data class Info(val ui: InfoUi) : NoteSheet
}

/** The open dialog of the Note screen. */
sealed interface NoteDialog {
    /** Rename the note; [title] is the current title. */
    @Immutable
    data class Rename(val title: String) : NoteDialog

    /** "Start over? The draft so far is discarded. Your recording is kept." */
    data object StartOver : NoteDialog

    /** "Transcribe again? Earlier versions can't be kept because the audio changed." (a non-OK document). */
    data object TranscribeFresh : NoteDialog

    /** "Stop transcription and move to trash?" (Move to trash while the job runs). */
    data object StopAndTrash : NoteDialog

    /** A rename, merge or version change that [reason] does not allow. */
    @Immutable
    data class ReadOnly(val reason: ReadOnlyReason) : NoteDialog
}
