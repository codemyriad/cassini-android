package org.cassini.ui.library

import androidx.compose.runtime.Immutable
import org.cassini.ui.note.ExportKind
import org.cassini.ui.note.NoteAction
import org.cassini.ui.banner.BannerAction as BannerButton

/** What the Library asks for (features.md section 1). */
sealed interface LibraryEvent {
    @Immutable
    data class Query(val text: String) : LibraryEvent

    data object ClearQuery : LibraryEvent

    @Immutable
    data class OpenNote(val id: String) : LibraryEvent

    /** A long press on a row: open the Note actions sheet. */
    @Immutable
    data class LongPress(val id: String) : LibraryEvent

    data object Record : LibraryEvent

    data object ReturnToRecording : LibraryEvent

    data object Import : LibraryEvent

    data object OpenTrash : LibraryEvent

    data object OpenSettings : LibraryEvent

    /** The transcription banner: the running note, or Settings for a standalone download. */
    data object OpenProgress : LibraryEvent

    /** "· Resume" on a paused row. */
    @Immutable
    data class Resume(val id: String) : LibraryEvent

    /** "· Try again" on a failed row. */
    @Immutable
    data class TryAgain(val id: String) : LibraryEvent

    /** The row's TalkBack custom action Rename. */
    @Immutable
    data class Rename(val id: String) : LibraryEvent

    /** The row's TalkBack custom action Move to trash. */
    @Immutable
    data class Trash(val id: String) : LibraryEvent

    @Immutable
    data class BannerAction(val id: String, val action: BannerButton) : LibraryEvent

    @Immutable
    data class DismissBanner(val id: String) : LibraryEvent

    data object Undo : LibraryEvent

    data object SnackbarTimeout : LibraryEvent

    /** A row of the Note actions sheet. */
    @Immutable
    data class SheetAction(val action: NoteAction) : LibraryEvent

    /** A row of the subtitle chooser ([LibrarySheet.SubtitleFormat]): export note [id] as [kind]. */
    @Immutable
    data class Export(val id: String, val kind: ExportKind) : LibraryEvent

    data object DismissSheet : LibraryEvent

    @Immutable
    data class RenameConfirmed(val id: String, val title: String) : LibraryEvent

    /** The confirm button of [LibraryUiState.dialog] (Start over, Transcribe again, Stop and move to trash). */
    data object ConfirmDialog : LibraryEvent

    data object DismissDialog : LibraryEvent
}
