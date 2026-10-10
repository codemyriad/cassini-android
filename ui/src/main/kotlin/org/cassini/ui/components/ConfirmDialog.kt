// Composables are nouns in PascalCase. The file is named after its composables, not after ConfirmSpec.
@file:Suppress("FunctionNaming", "MatchingDeclarationName")

package org.cassini.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.cassini.ui.R

/**
 * The copy of a confirmation: [title], an optional [body], and the [confirm] label of the confirm button (red when
 * [destructive]). A null [confirm] makes a notice with a single "Close" button (for example "Wait until
 * transcription finishes").
 */
@Immutable
data class ConfirmSpec(
    val title: String,
    val body: String? = null,
    val confirm: String? = null,
    val destructive: Boolean = false,
)

/**
 * A confirmation in its [CassiniDialog] window: Cancel and the confirm button, or only Close for a notice. Every
 * screen's yes/no questions use it (Start over, Move to trash, Delete for good, Forget, Merge).
 */
@Composable
fun ConfirmDialog(spec: ConfirmSpec, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    CassiniDialog(
        onDismissRequest = onDismiss,
        title = spec.title,
        actions = { ConfirmActions(spec, onConfirm, onDismiss) },
        body = spec.body,
    )
}

/** The same confirmation drawn inline, for screenshots (`DialogBoardFrame`). */
@Composable
fun ConfirmDialogContent(
    spec: ConfirmSpec,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CassiniDialogContent(
        title = spec.title,
        actions = { ConfirmActions(spec, onConfirm, onDismiss) },
        modifier = modifier,
        body = spec.body,
    )
}

@Composable
private fun ConfirmActions(spec: ConfirmSpec, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val confirm = spec.confirm
    if (confirm == null) {
        TextButton(stringResource(R.string.ui_action_close), onClick = onDismiss)
    } else {
        DialogActions(confirm, onConfirm = onConfirm, onCancel = onDismiss, destructive = spec.destructive)
    }
}
