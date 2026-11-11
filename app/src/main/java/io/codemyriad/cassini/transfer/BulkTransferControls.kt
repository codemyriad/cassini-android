@file:Suppress("FunctionNaming")

package io.codemyriad.cassini.transfer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.codemyriad.cassini.R
import io.codemyriad.cassini.data.notes.ArchiveFailure
import io.codemyriad.cassini.data.notes.ImportSummary
import io.codemyriad.cassini.ui.R as UiR

/** The explicit selector keeps bulk selection separate from a row's Open/long-press actions. */
@Composable
fun BulkTransferControls(transfers: BulkTransfers) {
    val state by transfers.state.collectAsStateWithLifecycle()
    var choosing by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var selectionInitialized by rememberSaveable { mutableStateOf(false) }
    var choices by remember { mutableStateOf<List<BulkTransfers.Choice>>(emptyList()) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) transfers.export(selected, uri)
    }
    LaunchedEffect(choosing) {
        if (choosing) {
            choices = transfers.choices()
            if (!selectionInitialized) {
                selected = ArrayList(choices.map { it.id })
                selectionInitialized = true
            }
        }
    }
    TextButton(
        onClick = {
            selectionInitialized = false
            choosing = true
        },
        enabled = !state.running,
    ) { Text(stringResource(R.string.export_zip_all)) }
    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text(stringResource(R.string.export_zip_all)) },
            text = {
                ExportChoices(choices, selected, { selected = it }) {
                    selected = choices.map { it.id }
                    choosing = false
                    save.launch("Cassini.zip")
                }
            },
            confirmButton = {
                if (selected.isNotEmpty()) {
                    TextButton(onClick = {
                        choosing = false
                        save.launch("Cassini.zip")
                    }) { Text(stringResource(R.string.export_zip_selected)) }
                }
            },
            dismissButton = {
                TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
    if (state != BulkTransfers.TransferState()) {
        TransferResult(state, transfers)
    }
}

@Composable
private fun ExportChoices(
    choices: List<BulkTransfers.Choice>,
    selected: List<String>,
    onChange: (List<String>) -> Unit,
    onAll: () -> Unit,
) {
    Column {
        TextButton(onClick = onAll) { Text(stringResource(R.string.export_zip_all)) }
        LazyColumn(Modifier.heightIn(max = 360.dp)) {
            items(choices, key = { it.id }) { choice ->
                Row(
                    Modifier.toggleable(
                        value = choice.id in selected,
                        role = Role.Checkbox,
                        onValueChange = { checked ->
                            onChange(if (checked) selected + choice.id else selected - choice.id)
                        },
                    ),
                ) {
                    Checkbox(checked = choice.id in selected, onCheckedChange = null)
                    Text(choice.title)
                }
            }
        }
    }
}

@Composable
private fun TransferResult(state: BulkTransfers.TransferState, transfers: BulkTransfers) {
    val heading: (@Composable () -> Unit)? = if (state.running || state.cancelled) {
        { TransferHeading(state) }
    } else {
        null
    }
    AlertDialog(
        onDismissRequest = { transfers.dismiss() },
        title = heading,
        text = {
            Column {
                state.summary?.let { SummaryParts(it) }
                state.exported?.let { Text(pluralStringResource(R.plurals.export_zip_done, it, it)) }
                if (state.failed) {
                    val resource = if (state.exporting) UiR.string.ui_actions_save_failed else UiR.string.ui_banner_import_open_title
                    Text(stringResource(resource))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (state.running) transfers.cancel() else transfers.dismiss() }) {
                Text(stringResource(if (state.running) R.string.common_cancel else R.string.common_done))
            }
        },
    )
}

@Composable
private fun TransferHeading(state: BulkTransfers.TransferState) {
    if (state.cancelled) {
        Text(stringResource(R.string.import_cancelled))
    } else {
        val resource = if (state.exporting) R.string.export_zip_progress else R.string.import_progress
        Text(stringResource(resource, state.done, state.total))
    }
}

@Composable
private fun SummaryParts(summary: ImportSummary) {
    val parts = listOf(
        summary.imported to R.plurals.import_summary_imported,
        summary.duplicates to R.plurals.import_summary_duplicates,
        summary.unsupported to R.plurals.import_summary_unsupported,
        summary.failed to R.plurals.import_summary_failed,
    )
    parts.filter { it.first > 0 }.forEach { (count, resource) -> Text(pluralStringResource(resource, count, count)) }
    summary.archiveFailures.forEach { reason ->
        val resource = when (reason) {
            ArchiveFailure.DAMAGED -> R.string.import_zip_failed_damaged
            ArchiveFailure.ENCRYPTED -> R.string.import_zip_failed_encrypted
            ArchiveFailure.UNSAFE_PATHS -> R.string.import_zip_failed_unsafe_paths
            ArchiveFailure.TOO_LARGE -> R.string.import_zip_failed_too_large
            ArchiveFailure.EMPTY -> R.string.import_zip_failed_empty
        }
        Text(stringResource(resource))
    }
}
