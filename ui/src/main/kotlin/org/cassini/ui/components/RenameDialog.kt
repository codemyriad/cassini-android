// Composables are nouns in PascalCase.
@file:Suppress("FunctionNaming")

package org.cassini.ui.components

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import org.cassini.ui.R

/** The longest note title a rename accepts (features.md section 6). */
const val TITLE_MAX_LENGTH = 200

/**
 * The note Rename dialog (features.md section 6) in its [CassiniDialog] window: the title field prefilled with
 * [title], Cancel and Save. Save is disabled until the title changes; a blank title shows "Enter a title." and
 * disables it too. Input stops at [TITLE_MAX_LENGTH] characters. Save calls [onConfirm] with the trimmed title.
 */
@Composable
fun RenameDialog(title: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var value by rememberSaveable(title) { mutableStateOf(title) }
    CassiniDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.ui_actions_rename_title),
        actions = { RenameActions(title, value, onConfirm, onDismiss) },
        content = { RenameField(title, value, onValueChange = { value = it }, onConfirm = onConfirm) },
    )
}

/** The same dialog drawn inline, for screenshots (`DialogBoardFrame`); [value] is the field text, [title] the old one. */
@Composable
fun RenameDialogContent(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    CassiniDialogContent(
        title = stringResource(R.string.ui_actions_rename_title),
        actions = { RenameActions(title, value, onConfirm, onDismiss) },
        content = { RenameField(title, value, onValueChange, onConfirm, showFocus = true) },
    )
}

@Composable
private fun RenameField(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: (String) -> Unit,
    showFocus: Boolean? = null,
) {
    val blank = value.isBlank()
    CassiniTextField(
        value = value,
        onValueChange = { onValueChange(it.take(TITLE_MAX_LENGTH)) },
        label = stringResource(R.string.ui_title_label),
        error = if (blank) stringResource(R.string.ui_error_title_blank) else null,
        showFocus = showFocus,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { if (canSave(title, value)) onConfirm(value.trim()) }),
    )
}

@Composable
private fun RenameActions(title: String, value: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    DialogActions(
        confirmLabel = stringResource(R.string.ui_action_save),
        onConfirm = { onConfirm(value.trim()) },
        onCancel = onDismiss,
        confirmEnabled = canSave(title, value),
    )
}

private fun canSave(title: String, value: String): Boolean = value.isNotBlank() && value.trim() != title
