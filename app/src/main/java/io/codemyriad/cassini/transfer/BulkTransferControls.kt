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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.codemyriad.cassini.R

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
    ) { Text(stringResource(R.string.bulk_export)) }
    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text(stringResource(R.string.bulk_export)) },
            text = { ExportChoices(choices, selected) { selected = ArrayList(it) } },
            confirmButton = {
                TextButton(enabled = selected.isNotEmpty(), onClick = {
                    choosing = false
                    save.launch("Cassini notes.zip")
                }) { Text(stringResource(R.string.bulk_save)) }
            },
            dismissButton = {
                TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.bulk_cancel)) }
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
) {
    Column {
        Text(stringResource(R.string.bulk_documents_only))
        TextButton(onClick = { onChange(choices.map { it.id }) }) { Text(stringResource(R.string.bulk_all)) }
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
    AlertDialog(
        onDismissRequest = { transfers.dismiss() },
        title = { Text(stringResource(if (state.running) R.string.bulk_working else R.string.bulk_finished)) },
        text = {
            val summary = state.summary
            val message = when {
                summary != null -> stringResource(
                    R.string.bulk_summary,
                    summary.imported,
                    summary.duplicates,
                    summary.ignored,
                    summary.failed,
                )
                state.exported != null -> stringResource(R.string.bulk_exported, state.exported)
                state.failed -> stringResource(R.string.bulk_failed)
                else -> stringResource(R.string.bulk_working)
            }
            Text(message)
        },
        confirmButton = {
            TextButton(onClick = { if (state.running) transfers.cancel() else transfers.dismiss() }) {
                Text(stringResource(if (state.running) R.string.bulk_cancel else R.string.bulk_done))
            }
        },
    )
}
