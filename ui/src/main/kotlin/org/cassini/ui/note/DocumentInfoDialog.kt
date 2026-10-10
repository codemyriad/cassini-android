// Composables are nouns in PascalCase; the numbers are the values of docs/design/ui.md section 8 (no board).
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.note

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.cassini.ui.R
import org.cassini.ui.components.CassiniDialog
import org.cassini.ui.components.CassiniDialogContent
import org.cassini.ui.components.LargeChip
import org.cassini.ui.components.TextButton
import org.cassini.ui.format.Counts
import org.cassini.ui.format.Dates
import org.cassini.ui.format.Durations
import org.cassini.ui.format.Languages
import org.cassini.ui.format.LocalFormat
import org.cassini.ui.format.Sizes
import org.cassini.ui.theme.CassiniTheme

/**
 * Document info (no board; features.md section 6; ui.md section 8) in its [CassiniDialog] window, with a single
 * Close button. The rows scroll inside the card when they do not fit.
 */
@Composable
fun DocumentInfoDialog(ui: InfoUi, onDismiss: () -> Unit) {
    CassiniDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.ui_actions_document_info),
        actions = { TextButton(stringResource(R.string.ui_action_close), onClick = onDismiss) },
        content = { InfoRows(ui) },
    )
}

/** The same dialog drawn inline, for screenshots (`DialogBoardFrame`). */
@Composable
fun DocumentInfoDialogContent(ui: InfoUi, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    CassiniDialogContent(
        title = stringResource(R.string.ui_actions_document_info),
        actions = { TextButton(stringResource(R.string.ui_action_close), onClick = onDismiss) },
        modifier = modifier,
        content = { InfoRows(ui) },
    )
}

/**
 * The key/value rows, 12 dp apart (key `caption` text2, value `body14` ink, 2 dp between): the trust chip, title,
 * recorded, duration, "2 versions · 3 speakers"; then "Selected version" (`section`) with language, the
 * transcription line (and "Resumed from 12:40"), model, speakers (and "2 names from saved voices"); then file size,
 * the meeting id (selectable `monoSmall`) and the read error of a damaged document. Null values hide their row.
 */
@Composable
@Suppress("LongMethod") // One row per fact of features.md section 6.
private fun ColumnScope.InfoRows(ui: InfoUi) {
    val t = CassiniTheme.type
    Column(
        Modifier
            .weight(1f, fill = false)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        InfoRow(stringResource(R.string.ui_info_trust)) {
            val (label, tone) = trustChip(ui.trust)
            LargeChip(text = label, tone = tone, modifier = Modifier.padding(top = 2.dp))
        }
        InfoRow(stringResource(R.string.ui_info_title), ui.title)
        InfoRow(stringResource(R.string.ui_info_recorded), Dates.longDateTime(ui.recordedDate, ui.recordedTime))
        InfoRow(stringResource(R.string.ui_info_duration), Durations.clock(ui.durationMs))
        InfoRow(
            stringResource(R.string.ui_info_contents),
            stringResource(
                R.string.ui_join_dot,
                Counts.plural(R.plurals.ui_count_versions, ui.versions),
                Counts.plural(R.plurals.ui_count_speakers, ui.speakers),
            ),
        )
        Text(
            text = stringResource(R.string.ui_info_version).uppercase(LocalFormat.current.locale),
            modifier = Modifier
                .padding(top = 4.dp)
                .semantics { heading() },
            style = t.section,
            color = CassiniTheme.colors.text2,
        )
        ui.language?.let { InfoRow(stringResource(R.string.ui_info_language), Languages.name(it)) }
        transcriptionLine(ui)?.let { InfoRow(stringResource(R.string.ui_info_transcription), it) }
        ui.model?.let { InfoRow(stringResource(R.string.ui_info_model), it) }
        speakersLine(ui)?.let { InfoRow(stringResource(R.string.ui_info_speakers), it) }
        InfoRow(stringResource(R.string.ui_info_file_size), Sizes.storage(ui.fileBytes))
        ui.meetingId?.let { id ->
            InfoRow(stringResource(R.string.ui_info_meeting_id)) {
                SelectionContainer { Text(text = id, style = t.monoSmall, color = CassiniTheme.colors.ink) }
            }
        }
        ui.readError?.let { InfoRow(stringResource(R.string.ui_info_read_error), it) }
    }
}

/** "Transcribed on this phone in 4 min 12 s · 11× realtime", then "Resumed from 12:40" on its own line. */
@Composable
private fun transcriptionLine(ui: InfoUi): String? {
    val processing = ui.processingMs
    val factor = ui.realtimeFactor
    val lines = listOfNotNull(
        if (processing != null && factor != null) {
            stringResource(
                R.string.ui_info_transcribed_in,
                Durations.compact(processing),
                Counts.speed(factor),
            )
        } else {
            null
        },
        ui.resumedFromMs?.let { stringResource(R.string.ui_info_resumed_from, Durations.clock(it)) },
    )
    return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
}

/** "Speakers told apart on this phone" or "All words shown as one voice", then "2 names from saved voices". */
@Composable
private fun speakersLine(ui: InfoUi): String? {
    val lines = listOfNotNull(
        ui.speakersToldApart?.let {
            stringResource(if (it) R.string.ui_info_speakers_apart else R.string.ui_info_one_voice)
        },
        if (ui.namesFromVoices > 0) Counts.plural(R.plurals.ui_count_names_from_voices, ui.namesFromVoices) else null,
    )
    return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
}

@Composable
private fun InfoRow(key: String, value: String) {
    InfoRow(key) { Text(text = value, style = CassiniTheme.type.body14, color = CassiniTheme.colors.ink) }
}

@Composable
private fun InfoRow(key: String, value: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = key, style = CassiniTheme.type.caption, color = CassiniTheme.colors.text2)
        value()
    }
}
