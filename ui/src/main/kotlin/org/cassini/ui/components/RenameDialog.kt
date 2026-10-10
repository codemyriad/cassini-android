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

/** The longest saved-voice name (features.md sections 4 and 10). */
const val NAME_MAX_LENGTH = 80

/**
 * What a rename dialog edits: a note title (the default: "Rename", label "Title", 200 characters, sentence case) or
 * a saved voice's name ([RenameKind.Voice]: "Rename saved voice", label "Name", 80 characters, words capitalised).
 */
enum class RenameKind { Title, Voice }

/**
 * The Rename dialog (features.md sections 6 and 10) in its [CassiniDialog] window: the field prefilled with [title],
 * Cancel and Save. Save is disabled until the text changes; a blank text shows "Enter a title." ("Enter a name.")
 * and disables it too. Input stops at the [kind]'s length limit. Save calls [onConfirm] with the trimmed text.
 */
@Composable
fun RenameDialog(
    title: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    kind: RenameKind = RenameKind.Title,
) {
    var value by rememberSaveable(title) { mutableStateOf(title) }
    CassiniDialog(
        onDismissRequest = onDismiss,
        title = dialogTitle(kind),
        actions = { RenameActions(title, value, onConfirm, onDismiss) },
        content = { RenameField(kind, title, value, onValueChange = { value = it }, onConfirm = onConfirm) },
    )
}

/** The same dialog drawn inline, for screenshots (`DialogBoardFrame`); [value] is the field text, [title] the old one. */
@Composable
@Suppress("LongParameterList") // The old and new text, the three callbacks and the kind.
fun RenameDialogContent(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    kind: RenameKind = RenameKind.Title,
) {
    CassiniDialogContent(
        title = dialogTitle(kind),
        actions = { RenameActions(title, value, onConfirm, onDismiss) },
        content = { RenameField(kind, title, value, onValueChange, onConfirm, showFocus = true) },
    )
}

@Composable
private fun dialogTitle(kind: RenameKind): String = stringResource(
    when (kind) {
        RenameKind.Title -> R.string.ui_actions_rename_title
        RenameKind.Voice -> R.string.ui_voices_rename_title
    },
)

@Composable
@Suppress("LongParameterList") // The kind, the old and new text, both callbacks and the forced focus look.
private fun RenameField(
    kind: RenameKind,
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: (String) -> Unit,
    showFocus: Boolean? = null,
) {
    val voice = kind == RenameKind.Voice
    val blank = value.isBlank()
    val max = if (voice) NAME_MAX_LENGTH else TITLE_MAX_LENGTH
    CassiniTextField(
        value = value,
        onValueChange = { onValueChange(it.take(max)) },
        label = stringResource(if (voice) R.string.ui_name_label else R.string.ui_title_label),
        error = when {
            !blank -> null
            voice -> stringResource(R.string.ui_error_name_blank)
            else -> stringResource(R.string.ui_error_title_blank)
        },
        showFocus = showFocus,
        keyboardOptions = KeyboardOptions(
            capitalization = if (voice) KeyboardCapitalization.Words else KeyboardCapitalization.Sentences,
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
