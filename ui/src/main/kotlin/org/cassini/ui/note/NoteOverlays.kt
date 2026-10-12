// Composables are nouns in PascalCase.
@file:Suppress("FunctionNaming")

package org.cassini.ui.note

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.cassini.ui.R
import org.cassini.ui.components.ConfirmDialog
import org.cassini.ui.components.ConfirmDialogContent
import org.cassini.ui.components.ConfirmSpec
import org.cassini.ui.components.RenameDialog
import org.cassini.ui.components.RenameDialogContent

/**
 * The open sheet and dialog of the Note screen, each in its own window: [NoteUiState.sheet] (Info is a dialog,
 * ui.md section 8) and then [NoteUiState.dialog] on top. A swipe, a scrim tap or back sends
 * [NoteEvent.DismissSheet] or [NoteEvent.DismissDialog].
 */
@Composable
internal fun NoteOverlays(state: NoteUiState, onEvent: (NoteEvent) -> Unit) {
    val dismissSheet = { onEvent(NoteEvent.DismissSheet) }
    when (val sheet = state.sheet) {
        is NoteSheet.Actions -> NoteActionsSheet(
            ui = sheet.ui,
            onAction = { onEvent(NoteEvent.SheetAction(it)) },
            onDismiss = dismissSheet,
        )
        is NoteSheet.NameSpeaker -> NameSpeakerSheet(
            ui = sheet.ui,
            onEvent = { onEvent(NoteEvent.NameSheet(it)) },
            onDismiss = dismissSheet,
        )
        is NoteSheet.Merge -> MergeSheet(
            ui = sheet.ui,
            onEvent = { onEvent(NoteEvent.Merge(it)) },
            onDismiss = dismissSheet,
        )
        is NoteSheet.Versions -> VersionsSheet(
            ui = sheet.ui,
            onPick = { onEvent(NoteEvent.PickVersion(it)) },
            onDismiss = dismissSheet,
        )
        NoteSheet.SubtitleFormat -> SubtitleFormatSheet(
            onExport = { onEvent(NoteEvent.Export(it)) },
            onDismiss = dismissSheet,
        )
        is NoteSheet.Info -> DocumentInfoDialog(ui = sheet.ui, onDismiss = dismissSheet)
        null -> Unit
    }
    when (val dialog = state.dialog) {
        is NoteDialog.Rename -> RenameDialog(
            title = dialog.title,
            onConfirm = { onEvent(NoteEvent.RenameConfirmed(it)) },
            onDismiss = { onEvent(NoteEvent.DismissDialog) },
        )
        null -> Unit
        else -> ConfirmDialog(
            spec = noteConfirmSpec(dialog),
            onConfirm = { onEvent(NoteEvent.ConfirmDialog) },
            onDismiss = { onEvent(NoteEvent.DismissDialog) },
        )
    }
}

/**
 * A Note dialog drawn inline, for screenshots (`DialogBoardFrame`): the Rename dialog with its title unchanged, or
 * the confirmation of [dialog].
 */
@Composable
fun NoteDialogContent(dialog: NoteDialog, onEvent: (NoteEvent) -> Unit) {
    if (dialog is NoteDialog.Rename) {
        RenameDialogContent(
            title = dialog.title,
            value = dialog.title,
            onValueChange = {},
            onConfirm = { onEvent(NoteEvent.RenameConfirmed(it)) },
            onDismiss = { onEvent(NoteEvent.DismissDialog) },
        )
    } else {
        ConfirmDialogContent(
            spec = noteConfirmSpec(dialog),
            onConfirm = { onEvent(NoteEvent.ConfirmDialog) },
            onDismiss = { onEvent(NoteEvent.DismissDialog) },
        )
    }
}

/**
 * The confirmations of the Note screen (features.md sections 3, 3.2 and 6): Start over (red), Transcribe again
 * over a changed document, Stop transcription and move to trash (red), and the read-only notice with Close.
 */
@Composable
internal fun noteConfirmSpec(dialog: NoteDialog): ConfirmSpec = when (dialog) {
    NoteDialog.StartOver -> startOverSpec()
    NoteDialog.TranscribeFresh -> transcribeFreshSpec()
    NoteDialog.StopAndTrash -> stopAndTrashSpec()
    is NoteDialog.ReadOnly -> ConfirmSpec(title = readOnlyMessage(dialog.reason))
    // Rename has its own dialog; this copy is never shown.
    is NoteDialog.Rename -> ConfirmSpec(title = stringResource(R.string.ui_actions_rename_title))
}

/** "Start over?" — "The draft so far is discarded. Your recording is kept." [Start over] (also from the Library). */
@Composable
internal fun startOverSpec(): ConfirmSpec = ConfirmSpec(
    title = stringResource(R.string.ui_note_start_over_title),
    body = stringResource(R.string.ui_note_start_over_body),
    confirm = stringResource(R.string.ui_action_start_over),
    destructive = true,
)

/** "Transcribe again?" — "Earlier versions can't be kept because the audio changed." (also from the Library). */
@Composable
internal fun transcribeFreshSpec(): ConfirmSpec = ConfirmSpec(
    title = stringResource(R.string.ui_actions_fresh_title),
    body = stringResource(R.string.ui_actions_fresh_body),
    confirm = stringResource(R.string.ui_action_transcribe_again),
)

/** "Stop transcription and move to trash?" — "Kept for 30 days, then deleted." [Move to trash] (also the Library). */
@Composable
internal fun stopAndTrashSpec(): ConfirmSpec = ConfirmSpec(
    title = stringResource(R.string.ui_actions_trash_running),
    body = stringResource(R.string.ui_actions_trash_body),
    confirm = stringResource(R.string.ui_action_move_to_trash),
    destructive = true,
)

/** What a rename, merge or version change says while [reason] forbids it. */
@Composable
internal fun readOnlyMessage(reason: ReadOnlyReason): String = stringResource(
    when (reason) {
        ReadOnlyReason.JOB_PENDING -> R.string.ui_note_wait_for_transcription
        ReadOnlyReason.TRUST -> R.string.ui_note_fix_transcript_first
        ReadOnlyReason.SAVING -> R.string.ui_note_naming_closed
    },
)
