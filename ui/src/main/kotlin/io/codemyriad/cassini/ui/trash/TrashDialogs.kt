// Composables are nouns in PascalCase.
@file:Suppress("FunctionNaming")

package io.codemyriad.cassini.ui.trash

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.components.ConfirmDialog
import io.codemyriad.cassini.ui.components.ConfirmDialogContent
import io.codemyriad.cassini.ui.components.ConfirmSpec
import io.codemyriad.cassini.ui.format.Counts

/** A Trash confirmation in its window: Delete for good, or Empty (features.md section 8). */
@Composable
fun TrashDialogWindow(dialog: TrashDialog, onEvent: (TrashEvent) -> Unit) {
    ConfirmDialog(
        spec = trashConfirmSpec(dialog),
        onConfirm = { onEvent(TrashEvent.ConfirmDialog) },
        onDismiss = { onEvent(TrashEvent.DismissDialog) },
    )
}

/** The same confirmation drawn inline, for screenshots (`DialogBoardFrame`). */
@Composable
fun TrashDialogContent(dialog: TrashDialog, onEvent: (TrashEvent) -> Unit) {
    ConfirmDialogContent(
        spec = trashConfirmSpec(dialog),
        onConfirm = { onEvent(TrashEvent.ConfirmDialog) },
        onDismiss = { onEvent(TrashEvent.DismissDialog) },
    )
}

/**
 * "Delete “<title>” for good?" — "Its audio, transcripts and voiceprints from this note are deleted. This can’t be
 * undone." or "Delete 2 notes for good?" — "Their audio, …"; both with the red Delete.
 */
@Composable
private fun trashConfirmSpec(dialog: TrashDialog): ConfirmSpec = when (dialog) {
    is TrashDialog.Delete -> ConfirmSpec(
        title = stringResource(R.string.ui_trash_delete_title, dialog.title),
        body = stringResource(R.string.ui_trash_delete_body),
        confirm = stringResource(R.string.ui_action_delete),
        destructive = true,
    )
    is TrashDialog.EmptyAll -> ConfirmSpec(
        title = Counts.plural(R.plurals.ui_trash_delete_all_title, dialog.count),
        body = stringResource(R.string.ui_trash_delete_all_body),
        confirm = stringResource(R.string.ui_action_delete),
        destructive = true,
    )
}
