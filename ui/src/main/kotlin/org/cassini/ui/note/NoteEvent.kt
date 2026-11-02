package org.cassini.ui.note

import androidx.compose.runtime.Immutable
import org.cassini.ui.components.WordRef
import org.cassini.ui.banner.BannerAction as BannerButton

/**
 * What the Note screen asks for (features.md section 3). Stop and Remove from queue come from the cards; Resume,
 * Start over, Transcribe, Transcribe again and Move to trash arrive as [SheetAction] (the actions sheet) or
 * [BannerAction] (the pause and failure banners), so they have no event of their own.
 */
sealed interface NoteEvent {
    data object Back : NoteEvent

    /** The top bar More button: open the Note actions sheet. */
    data object More : NoteEvent

    /** The progress card's Stop. */
    data object Stop : NoteEvent

    /** The Queued card's Remove from queue. */
    data object RemoveFromQueue : NoteEvent

    /** A word tap: seek to the word and play. */
    @Immutable
    data class WordTap(val word: WordRef) : NoteEvent

    /** "Play from start of turn" (the turn's custom action). */
    @Immutable
    data class PlayTurn(val turnId: String) : NoteEvent

    /** A speaker label: open the Name sheet. */
    @Immutable
    data class SpeakerTap(val speakerId: String) : NoteEvent

    /** "Yes, Luca". */
    @Immutable
    data class AcceptSuggestion(val speakerId: String, val voiceId: String) : NoteEvent

    /** "Not Luca". */
    @Immutable
    data class RejectSuggestion(val speakerId: String, val voiceId: String) : NoteEvent

    data object PlayPause : NoteEvent

    @Immutable
    data class Seek(val positionMs: Long) : NoteEvent

    data object SeekFinished : NoteEvent

    @Immutable
    data class Skip(val forward: Boolean) : NoteEvent

    /** The speed button: the next playback speed (coordination D53). */
    data object NextSpeed : NoteEvent

    /** The user scrolled the transcript away from the active word: pause auto-follow. */
    data object PauseFollowing : NoteEvent

    /** The "Follow playback" chip. */
    data object FollowPlayback : NoteEvent

    /** The trust chip: show or hide the matching trust banner. */
    data object TrustChip : NoteEvent

    data object OpenVersions : NoteEvent

    @Immutable
    data class PickVersion(val index: Int) : NoteEvent

    data object OpenSearch : NoteEvent

    @Immutable
    data class SearchQuery(val query: String) : NoteEvent

    data object NextMatch : NoteEvent

    data object PreviousMatch : NoteEvent

    data object CloseSearch : NoteEvent

    /** A row of the Note actions sheet. */
    @Immutable
    data class SheetAction(val action: NoteAction) : NoteEvent

    /** An export, after the subtitle chooser for SRT and VTT. */
    @Immutable
    data class Export(val kind: ExportKind) : NoteEvent

    @Immutable
    data class RenameConfirmed(val title: String) : NoteEvent

    /** The confirm button of [NoteUiState.dialog]. */
    data object ConfirmDialog : NoteEvent

    @Immutable
    data class NameSheet(val event: NameSheetEvent) : NoteEvent

    @Immutable
    data class Merge(val event: MergeEvent) : NoteEvent

    @Immutable
    data class BannerAction(val id: String, val action: BannerButton) : NoteEvent

    @Immutable
    data class DismissBanner(val id: String) : NoteEvent

    data object DismissSheet : NoteEvent

    data object DismissDialog : NoteEvent

    data object SnackbarTimeout : NoteEvent
}
