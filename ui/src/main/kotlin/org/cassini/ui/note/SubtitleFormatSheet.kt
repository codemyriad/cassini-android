// Composables are nouns in PascalCase; the numbers follow board 06's sheet (docs/design/ui.md section 8).
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.note

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
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
import org.cassini.ui.theme.CassiniTheme
import org.cassini.ui.theme.Glyph

/**
 * The subtitle chooser (no board; features.md section 6; ui.md section 8) in its [CassiniSheet] window. A row sends
 * [onExport] with [ExportKind.SRT] or [ExportKind.VTT].
 */
@Composable
fun SubtitleFormatSheet(onExport: (ExportKind) -> Unit, onDismiss: () -> Unit) {
    CassiniSheet(onDismissRequest = onDismiss) { SubtitleFormatSheetContent(onExport) }
}

/**
 * The sheet's content, also drawn inline in screenshots, laid out like board 06: padding 12/12/20, the title
 * "Subtitles" (`noteTitle`, margin 12 horizontal and below), then "SubRip (.srt)" — "works in most video players"
 * and "WebVTT (.vtt)" — "for web players" as plain rows with the Subtitles icon.
 */
@Composable
fun SubtitleFormatSheetContent(onExport: (ExportKind) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 20.dp)) {
        Text(
            text = stringResource(R.string.ui_actions_subtitles),
            modifier = Modifier
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                .semantics { heading() },
            style = CassiniTheme.type.noteTitle,
            color = CassiniTheme.colors.ink,
        )
        SheetActionRow(
            label = stringResource(R.string.ui_subrip),
            glyph = Glyph.Subtitles,
            onClick = { onExport(ExportKind.SRT) },
            subtitle = stringResource(R.string.ui_actions_subrip_body),
        )
        SheetActionRow(
            label = stringResource(R.string.ui_webvtt),
            glyph = Glyph.Subtitles,
            onClick = { onExport(ExportKind.VTT) },
            subtitle = stringResource(R.string.ui_actions_webvtt_body),
        )
    }
}
