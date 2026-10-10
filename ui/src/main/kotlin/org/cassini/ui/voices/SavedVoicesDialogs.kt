// Composables are nouns in PascalCase.
@file:Suppress("FunctionNaming")

package org.cassini.ui.voices

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.cassini.ui.R
import org.cassini.ui.components.ConfirmDialog
import org.cassini.ui.components.ConfirmDialogContent
import org.cassini.ui.components.ConfirmSpec
import org.cassini.ui.components.RenameDialog
import org.cassini.ui.components.RenameDialogContent
import org.cassini.ui.components.RenameKind
import org.cassini.ui.format.Counts

/** A Saved voices dialog in its window (features.md section 10): Rename saved voice, Forget, Forget everyone. */
@Composable
fun SavedVoicesDialogWindow(dialog: SavedVoicesDialog, onEvent: (SavedVoicesEvent) -> Unit) {
    val dismiss = { onEvent(SavedVoicesEvent.DismissDialog) }
    when (dialog) {
        is SavedVoicesDialog.Rename -> RenameDialog(
            title = dialog.name,
            onConfirm = { onEvent(SavedVoicesEvent.RenameConfirmed(dialog.id, it)) },
            onDismiss = dismiss,
            kind = RenameKind.Voice,
        )
        else -> ConfirmDialog(
            spec = forgetSpec(dialog),
            onConfirm = { onEvent(SavedVoicesEvent.ConfirmDialog) },
            onDismiss = dismiss,
        )
    }
}

/** The same dialog drawn inline, for screenshots (`DialogBoardFrame`); a rename shows the name unchanged. */
@Composable
fun SavedVoicesDialogContent(dialog: SavedVoicesDialog, onEvent: (SavedVoicesEvent) -> Unit) {
    val dismiss = { onEvent(SavedVoicesEvent.DismissDialog) }
    when (dialog) {
        is SavedVoicesDialog.Rename -> RenameDialogContent(
            title = dialog.name,
            value = dialog.name,
            onValueChange = {},
            onConfirm = { onEvent(SavedVoicesEvent.RenameConfirmed(dialog.id, it)) },
            onDismiss = dismiss,
            kind = RenameKind.Voice,
        )
        else -> ConfirmDialogContent(
            spec = forgetSpec(dialog),
            onConfirm = { onEvent(SavedVoicesEvent.ConfirmDialog) },
            onDismiss = dismiss,
        )
    }
}

/**
 * "Forget <name>?" — "Cassini stops recognising this voice. Names already in your notes stay." [Forget], or
 * "Forget everyone?" — "Cassini stops recognising all 4 saved voices …" [Forget everyone]; both red.
 */
@Composable
private fun forgetSpec(dialog: SavedVoicesDialog): ConfirmSpec = when (dialog) {
    is SavedVoicesDialog.Forget -> ConfirmSpec(
        title = stringResource(R.string.ui_voices_forget_title, dialog.name),
        body = stringResource(R.string.ui_voices_forget_body),
        confirm = stringResource(R.string.ui_voices_forget_action),
        destructive = true,
    )
    is SavedVoicesDialog.ForgetEveryone -> ConfirmSpec(
        title = stringResource(R.string.ui_voices_forget_everyone_title),
        body = Counts.plural(R.plurals.ui_voices_forget_everyone_body, dialog.count),
        confirm = stringResource(R.string.ui_voices_forget_everyone_action),
        destructive = true,
    )
    // Rename has its own dialog; this copy is never shown.
    is SavedVoicesDialog.Rename -> ConfirmSpec(title = stringResource(R.string.ui_voices_rename_title))
}
