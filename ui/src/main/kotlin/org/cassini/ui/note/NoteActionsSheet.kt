// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 8.
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.note

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.cassini.ui.R
import org.cassini.ui.components.CassiniSheet
import org.cassini.ui.components.SheetActionRow
import org.cassini.ui.components.SheetDivider
import org.cassini.ui.components.SheetRowStyle
import org.cassini.ui.components.SheetSection
import org.cassini.ui.format.Counts
import org.cassini.ui.format.Dates
import org.cassini.ui.format.Durations
import org.cassini.ui.theme.CassiniTheme
import org.cassini.ui.theme.Glyph

/**
 * The Note actions sheet (board 06) in its [CassiniSheet] window. The Library (long press) and the Note screen
 * (More) both open it; a row sends [onAction] and a swipe or a scrim tap sends [onDismiss]. The rows do not close
 * the sheet themselves: the ViewModel drops it, or keeps it open while an export writes ([NoteActionsUi.busyRow]).
 */
@Composable
fun NoteActionsSheet(ui: NoteActionsUi, onAction: (NoteAction) -> Unit, onDismiss: () -> Unit) {
    CassiniSheet(onDismissRequest = onDismiss) { NoteActionsSheetContent(ui, onAction) }
}

/**
 * The sheet's content (board 06; features.md section 6), also drawn inline in screenshots (`SheetBoardFrame`):
 * padding 12 (below the handle; 10.5 measured) / 12 / 20; the title (margin 12 horizontal, 2 below; 3.5 measured) and
 * the meta line
 * "Yesterday, 15:10 · 23:50 · 3 speakers" (12 below); "Save a copy" with the document or audio file row, text and
 * subtitles; the divider; Rename, the transcribe row(s), Document info (documents only) and Move to trash (hidden
 * while recording). The content scrolls when it does not fit (large fonts, landscape).
 */
@Composable
fun NoteActionsSheetContent(ui: NoteActionsUi, onAction: (NoteAction) -> Unit, modifier: Modifier = Modifier) {
    val c = CassiniTheme.colors
    val document = ui.documentKind == DocumentKindUi.Document
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 12.dp, top = 10.5.dp, bottom = 20.dp),
    ) {
        // Board 06 puts the title 12 dp below the handle and the meta 2 dp below it. Android sets an 18 sp line's
        // glyphs 1.5 dp lower in the line than the browser, so the title starts 1.5 dp higher and keeps 3.5 below.
        Text(
            text = ui.title,
            modifier = Modifier
                .padding(start = 12.dp, end = 12.dp, bottom = 3.5.dp)
                .semantics { heading() },
            style = CassiniTheme.type.noteTitle,
            color = c.ink,
        )
        Text(
            text = metaLine(ui, document),
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            style = CassiniTheme.type.caption,
            color = c.text2,
        )
        SaveACopy(ui, document, onAction)
        SheetDivider()
        SheetActionRow(stringResource(R.string.ui_action_rename), Glyph.Pencil, { onAction(NoteAction.RENAME) })
        TranscribeRows(ui.transcribeRow, onAction)
        if (document) {
            SheetActionRow(
                label = stringResource(R.string.ui_actions_document_info),
                glyph = Glyph.Info,
                onClick = { onAction(NoteAction.DOCUMENT_INFO) },
            )
        }
        if (!ui.recording) {
            SheetActionRow(
                label = stringResource(R.string.ui_action_move_to_trash),
                glyph = Glyph.Trash,
                onClick = { onAction(NoteAction.MOVE_TO_TRASH) },
                subtitle = stringResource(R.string.ui_actions_trash_body),
                style = SheetRowStyle.Destructive,
            )
        }
    }
}

/** "Yesterday, 15:10 · 23:50", plus "· 3 speakers" for a document with speakers. */
@Composable
private fun metaLine(ui: NoteActionsUi, document: Boolean): String {
    val dayDuration = stringResource(
        R.string.ui_join_dot,
        Dates.dayTime(ui.recordedDay, ui.recordedAt),
        Durations.clock(ui.durationMs),
    )
    if (!document || ui.speakers <= 0) return dayDuration
    return stringResource(R.string.ui_join_dot, dayDuration, Counts.plural(R.plurals.ui_count_speakers, ui.speakers))
}

/**
 * "Save a copy": the featured row (Cassini document `.opus`, or the plain Audio file with its own extension), then
 * Transcript as text and Subtitles, disabled with the reason when [NoteActionsUi.textExportEnabled] is false.
 */
@Composable
private fun ColumnScope.SaveACopy(ui: NoteActionsUi, document: Boolean, onAction: (NoteAction) -> Unit) {
    SheetSection(stringResource(R.string.ui_actions_save_copy))
    val plain = ui.documentKind as? DocumentKindUi.Plain
    SheetActionRow(
        label = stringResource(if (document) R.string.ui_actions_document else R.string.ui_actions_audio_file),
        glyph = Glyph.Music,
        onClick = { onAction(NoteAction.SAVE_DOCUMENT) },
        extension = if (plain != null) {
            stringResource(R.string.ui_ext_any, plain.ext)
        } else {
            stringResource(R.string.ui_ext_opus)
        },
        subtitle = stringResource(
            if (document) R.string.ui_actions_document_body else R.string.ui_actions_audio_file_body,
        ),
        style = SheetRowStyle.Featured,
        busy = ui.busyRow == NoteAction.SAVE_DOCUMENT,
    )
    val reason = when {
        ui.textExportEnabled -> null
        document -> stringResource(R.string.ui_actions_not_readable)
        else -> stringResource(R.string.ui_actions_after_transcription)
    }
    SheetActionRow(
        label = stringResource(R.string.ui_actions_text),
        glyph = Glyph.TextFile,
        onClick = { onAction(NoteAction.SAVE_TEXT) },
        extension = stringResource(R.string.ui_ext_txt),
        subtitle = reason,
        enabled = ui.textExportEnabled,
        busy = ui.busyRow == NoteAction.SAVE_TEXT,
    )
    SheetActionRow(
        label = stringResource(R.string.ui_actions_subtitles),
        glyph = Glyph.Subtitles,
        onClick = { onAction(NoteAction.SAVE_SUBTITLES) },
        extension = stringResource(R.string.ui_ext_subtitles),
        subtitle = reason,
        enabled = ui.textExportEnabled,
        busy = ui.busyRow == NoteAction.SAVE_SUBTITLES,
    )
}

/**
 * The transcribe row in its form (features.md section 6): Transcribe (plain), Transcribe again with "Adds version N."
 * and "Speaker names carry over.", Stop and keep progress (running), Remove from queue (queued), or Resume and
 * Start over (paused).
 */
@Composable
private fun TranscribeRows(row: TranscribeRowUi, onAction: (NoteAction) -> Unit) {
    when (row) {
        TranscribeRowUi.Transcribe -> SheetActionRow(
            label = stringResource(R.string.ui_action_transcribe),
            glyph = Glyph.Retranscribe,
            onClick = { onAction(NoteAction.TRANSCRIBE) },
        )
        is TranscribeRowUi.TranscribeAgain -> {
            val adds = stringResource(R.string.ui_actions_adds_version, row.nextVersion)
            SheetActionRow(
                label = stringResource(R.string.ui_action_transcribe_again),
                glyph = Glyph.Retranscribe,
                onClick = { onAction(NoteAction.TRANSCRIBE_AGAIN) },
                subtitle = if (row.namesCarry) {
                    stringResource(R.string.ui_join_space, adds, stringResource(R.string.ui_actions_names_carry))
                } else {
                    adds
                },
            )
        }
        TranscribeRowUi.TranscribeAgainUnavailable -> SheetActionRow(
            label = stringResource(R.string.ui_action_transcribe_again),
            glyph = Glyph.Retranscribe,
            onClick = {},
            subtitle = stringResource(R.string.ui_actions_no_new_version),
            enabled = false,
        )
        TranscribeRowUi.Stop -> SheetActionRow(
            label = stringResource(R.string.ui_action_stop_keep_progress),
            glyph = Glyph.Pause,
            onClick = { onAction(NoteAction.STOP) },
        )
        TranscribeRowUi.RemoveFromQueue -> SheetActionRow(
            label = stringResource(R.string.ui_action_remove_from_queue),
            glyph = Glyph.Close,
            onClick = { onAction(NoteAction.REMOVE_FROM_QUEUE) },
        )
        TranscribeRowUi.ResumeOrStartOver -> {
            SheetActionRow(
                label = stringResource(R.string.ui_action_resume),
                glyph = Glyph.Play,
                onClick = { onAction(NoteAction.RESUME) },
            )
            SheetActionRow(
                label = stringResource(R.string.ui_action_start_over),
                glyph = Glyph.Retranscribe,
                onClick = { onAction(NoteAction.START_OVER) },
            )
        }
    }
}
