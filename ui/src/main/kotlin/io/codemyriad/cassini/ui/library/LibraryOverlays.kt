// Composables are nouns in PascalCase.
@file:Suppress("FunctionNaming")

package io.codemyriad.cassini.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.components.ConfirmDialog
import io.codemyriad.cassini.ui.components.ConfirmDialogContent
import io.codemyriad.cassini.ui.components.ConfirmSpec
import io.codemyriad.cassini.ui.components.RenameDialog
import io.codemyriad.cassini.ui.components.RenameDialogContent
import io.codemyriad.cassini.ui.note.DocumentInfoDialog
import io.codemyriad.cassini.ui.note.NoteActionsSheet
import io.codemyriad.cassini.ui.note.SubtitleFormatSheet
import io.codemyriad.cassini.ui.note.startOverSpec
import io.codemyriad.cassini.ui.note.stopAndTrashSpec
import io.codemyriad.cassini.ui.note.transcribeFreshSpec

/** A Library sheet in its window: the Note actions sheet, the subtitle chooser, or Document info (a dialog). */
@Composable
internal fun LibrarySheetWindow(sheet: LibrarySheet, onEvent: (LibraryEvent) -> Unit) {
    val dismiss = { onEvent(LibraryEvent.DismissSheet) }
    when (sheet) {
        is LibrarySheet.Actions -> NoteActionsSheet(
            ui = sheet.ui,
            onAction = { onEvent(LibraryEvent.SheetAction(it)) },
            onDismiss = dismiss,
        )
        is LibrarySheet.SubtitleFormat -> SubtitleFormatSheet(
            onExport = { onEvent(LibraryEvent.Export(sheet.id, it)) },
            onDismiss = dismiss,
        )
        is LibrarySheet.Info -> DocumentInfoDialog(ui = sheet.ui, onDismiss = dismiss)
    }
}

/** A Library dialog in its window: Rename, or a confirmation from the actions sheet. */
@Composable
internal fun LibraryDialogWindow(dialog: LibraryDialog, onEvent: (LibraryEvent) -> Unit) {
    val dismiss = { onEvent(LibraryEvent.DismissDialog) }
    when (dialog) {
        is LibraryDialog.Rename -> RenameDialog(
            title = dialog.title,
            onConfirm = { onEvent(LibraryEvent.RenameConfirmed(dialog.id, it)) },
            onDismiss = dismiss,
        )
        else -> ConfirmDialog(
            spec = libraryConfirmSpec(dialog),
            onConfirm = { onEvent(LibraryEvent.ConfirmDialog) },
            onDismiss = dismiss,
        )
    }
}

/**
 * A Library dialog drawn inline, for screenshots (`DialogBoardFrame`): the Rename dialog with its title unchanged,
 * or the confirmation of [dialog].
 */
@Composable
fun LibraryDialogContent(dialog: LibraryDialog, onEvent: (LibraryEvent) -> Unit) {
    if (dialog is LibraryDialog.Rename) {
        RenameDialogContent(
            title = dialog.title,
            value = dialog.title,
            onValueChange = {},
            onConfirm = { onEvent(LibraryEvent.RenameConfirmed(dialog.id, it)) },
            onDismiss = { onEvent(LibraryEvent.DismissDialog) },
        )
    } else {
        ConfirmDialogContent(
            spec = libraryConfirmSpec(dialog),
            onConfirm = { onEvent(LibraryEvent.ConfirmDialog) },
            onDismiss = { onEvent(LibraryEvent.DismissDialog) },
        )
    }
}

/** The confirmations the Library shares with the Note screen (features.md sections 3.2 and 6). */
@Composable
private fun libraryConfirmSpec(dialog: LibraryDialog): ConfirmSpec = when (dialog) {
    is LibraryDialog.StartOver -> startOverSpec()
    is LibraryDialog.TranscribeFresh -> transcribeFreshSpec()
    is LibraryDialog.StopAndTrash -> stopAndTrashSpec()
    // Rename has its own dialog; this copy is never shown.
    is LibraryDialog.Rename -> ConfirmSpec(title = stringResource(R.string.ui_actions_rename_title))
}
