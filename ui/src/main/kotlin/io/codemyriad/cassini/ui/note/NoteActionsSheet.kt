// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 8.
@file:Suppress("FunctionNaming", "MagicNumber")

package io.codemyriad.cassini.ui.note

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.components.CassiniSheet
import io.codemyriad.cassini.ui.components.RowShare
import io.codemyriad.cassini.ui.components.SheetActionRow
import io.codemyriad.cassini.ui.components.SheetDivider
import io.codemyriad.cassini.ui.components.SheetRowStyle
import io.codemyriad.cassini.ui.components.SheetSection
import io.codemyriad.cassini.ui.format.Counts
import io.codemyriad.cassini.ui.format.Dates
import io.codemyriad.cassini.ui.format.Durations
import io.codemyriad.cassini.ui.theme.CassiniTheme
import io.codemyriad.cassini.ui.theme.Glyph

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
 * "Yesterday, 15:10 · 23:50 · 3 speakers" (12 below); "Save a copy" with the Cassini file or audio row, Markdown and
 * More formats; the divider; Rename, the transcribe row(s), Document info (documents only) and Move to trash (hidden
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
        TranscribeRows(ui.transcribeRow, stopBusy = ui.busyRow == NoteAction.STOP, onAction = onAction)
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
        ui.recordedLabel ?: Dates.dayTime(ui.recordedDay, ui.recordedAt),
        Durations.clock(ui.durationMs),
    )
    if (!document || ui.speakers <= 0) return dayDuration
    return stringResource(R.string.ui_join_dot, dayDuration, Counts.plural(R.plurals.ui_count_speakers, ui.speakers))
}

/**
 * "Save a copy" (L-11, cassini-ux D99 and `PARITY.md` "Export formats"): the featured row first, then Markdown, then
 * the "More formats" disclosure with Plain text, Subtitles (SRT) and Subtitles (WebVTT). The featured row is the
 * Cassini file (`.opus`) of a document, "Original audio" for an imported plain note and "Audio file" for an own
 * recording not yet transcribed. Without a completed transcript ([NoteActionsUi.textExportEnabled] false) the text
 * formats are hidden, not disabled, and one line says why. A row saves a copy through the system picker; its Share
 * button sends the same file to another app (coordination D53).
 */
@Composable
private fun ColumnScope.SaveACopy(ui: NoteActionsUi, document: Boolean, onAction: (NoteAction) -> Unit) {
    SheetSection(stringResource(R.string.ui_actions_save_copy))
    AudioRow(ui, document, onAction)
    if (!ui.textExportEnabled) {
        val why = if (ui.transcriptUnreadable) R.string.ui_actions_not_readable else R.string.ui_export_audio_only
        Text(
            text = stringResource(why),
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
            style = CassiniTheme.type.caption,
            color = CassiniTheme.colors.text2,
        )
        return
    }
    ExportRow(ui, MARKDOWN_ROW, onAction)
    MoreFormats(ui, onAction)
}

/** The featured row: the Cassini file, the Original audio of an imported plain note, or an own recording's audio. */
@Composable
private fun AudioRow(ui: NoteActionsUi, document: Boolean, onAction: (NoteAction) -> Unit) {
    val plain = ui.documentKind as? DocumentKindUi.Plain
    val (labelRes, bodyRes) = when {
        document -> R.string.ui_export_cassini_file to R.string.ui_export_cassini_file_body
        plain?.imported == true -> R.string.ui_export_original_audio to R.string.ui_export_original_audio_body
        else -> R.string.ui_actions_audio_file to R.string.ui_actions_audio_file_body
    }
    val label = stringResource(labelRes)
    SheetActionRow(
        label = label,
        glyph = Glyph.Music,
        onClick = { onAction(NoteAction.SAVE_DOCUMENT) },
        extension = if (plain != null) {
            stringResource(R.string.ui_ext_any, plain.ext)
        } else {
            stringResource(R.string.ui_ext_opus)
        },
        subtitle = stringResource(bodyRes),
        style = SheetRowStyle.Featured,
        busy = ui.busyRow == NoteAction.SAVE_DOCUMENT,
        share = rowShare(label) { onAction(NoteAction.SHARE_DOCUMENT) },
    )
}

/**
 * The "More formats" disclosure (closed when the sheet opens, open while one of its rows writes) and, when open,
 * Plain text, Subtitles (SRT) and Subtitles (WebVTT). TalkBack hears it as expandable / collapsible.
 */
@Composable
private fun MoreFormats(ui: NoteActionsUi, onAction: (NoteAction) -> Unit) {
    val busy = MORE_FORMAT_ROWS.any { it.save == ui.busyRow }
    var expanded by rememberSaveable(ui.noteId) { mutableStateOf(false) }
    val open = expanded || busy
    SheetActionRow(
        label = stringResource(R.string.ui_export_more_formats),
        glyph = if (open) Glyph.ChevronUp else Glyph.ChevronDown,
        onClick = { expanded = !open },
        modifier = Modifier.semantics {
            if (open) {
                collapse {
                    expanded = false
                    true
                }
            } else {
                expand {
                    expanded = true
                    true
                }
            }
        },
        enabled = !busy,
    )
    if (open) MORE_FORMAT_ROWS.forEach { ExportRow(ui, it, onAction) }
}

/** A text format of "Save a copy": its catalog label and description, mono extension, glyph and actions. */
private data class ExportFormatRow(
    @StringRes val label: Int,
    @StringRes val body: Int,
    @StringRes val extension: Int,
    val glyph: Glyph,
    val save: NoteAction,
    val share: NoteAction,
)

/** A text-format row with its Share button. */
@Composable
private fun ExportRow(ui: NoteActionsUi, row: ExportFormatRow, onAction: (NoteAction) -> Unit) {
    val text = stringResource(row.label)
    SheetActionRow(
        label = text,
        glyph = row.glyph,
        onClick = { onAction(row.save) },
        extension = stringResource(row.extension),
        subtitle = stringResource(row.body),
        busy = ui.busyRow == row.save,
        share = rowShare(text) { onAction(row.share) },
    )
}

private val MARKDOWN_ROW = ExportFormatRow(
    label = R.string.ui_actions_markdown,
    body = R.string.ui_markdown_description,
    extension = R.string.ui_ext_md,
    glyph = Glyph.TextFile,
    save = NoteAction.SAVE_MARKDOWN,
    share = NoteAction.SHARE_MARKDOWN,
)

/** The rows under "More formats", in the contract's order. */
private val MORE_FORMAT_ROWS = listOf(
    ExportFormatRow(
        label = R.string.ui_export_plain_text,
        body = R.string.ui_export_plain_text_body,
        extension = R.string.ui_ext_txt,
        glyph = Glyph.TextFile,
        save = NoteAction.SAVE_TEXT,
        share = NoteAction.SHARE_TEXT,
    ),
    ExportFormatRow(
        label = R.string.ui_export_srt,
        body = R.string.ui_export_srt_body,
        extension = R.string.ui_ext_srt,
        glyph = Glyph.Subtitles,
        save = NoteAction.SAVE_SRT,
        share = NoteAction.SHARE_SRT,
    ),
    ExportFormatRow(
        label = R.string.ui_export_vtt,
        body = R.string.ui_export_vtt_body,
        extension = R.string.ui_ext_vtt,
        glyph = Glyph.Subtitles,
        save = NoteAction.SAVE_VTT,
        share = NoteAction.SHARE_VTT,
    ),
)

/** The Share button of a "Save a copy" row, spoken as "Share [label]". */
@Composable
private fun rowShare(label: String, onClick: () -> Unit): RowShare =
    RowShare(stringResource(R.string.ui_actions_share, label), onClick)

/**
 * The transcribe row in its form (features.md section 6): Transcribe (plain), Transcribe again with "Adds version N."
 * and "Speaker names carry over.", Stop and keep progress (running), Remove from queue (queued), or Resume and
 * Start over (paused).
 */
@Composable
private fun TranscribeRows(row: TranscribeRowUi, stopBusy: Boolean, onAction: (NoteAction) -> Unit) {
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
            label = stringResource(R.string.ui_tx_stop_keep),
            glyph = Glyph.Pause,
            onClick = { onAction(NoteAction.STOP) },
            busy = stopBusy,
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
