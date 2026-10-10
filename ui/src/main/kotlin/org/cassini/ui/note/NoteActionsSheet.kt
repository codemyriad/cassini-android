// Composables are nouns in PascalCase.
@file:Suppress("FunctionNaming")

package org.cassini.ui.note

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.cassini.ui.components.CassiniSheet
import org.cassini.ui.format.Dates
import org.cassini.ui.format.Durations
import org.cassini.ui.theme.CassiniTheme

/**
 * The Note actions sheet (board 06) in its [CassiniSheet] window. The Library (long press) and the Note screen
 * (More) both open it; a row sends [onAction] and a swipe or a scrim tap sends [onDismiss].
 */
@Composable
fun NoteActionsSheet(ui: NoteActionsUi, onAction: (NoteAction) -> Unit, onDismiss: () -> Unit) {
    CassiniSheet(onDismissRequest = onDismiss) { NoteActionsSheetContent(ui, onAction) }
}

/**
 * The sheet's content, also drawn inline in screenshots (`SheetBoardFrame`). P8 step 4 draws the rows of board 06;
 * until then the content is the header only: the title and the "Yesterday, 15:10 · 23:50" meta line.
 */
@Suppress("UnusedParameter", "UNUSED_PARAMETER") // The rows that send onAction come in P8 step 4.
@Composable
fun NoteActionsSheetContent(ui: NoteActionsUi, onAction: (NoteAction) -> Unit, modifier: Modifier = Modifier) {
    val c = CassiniTheme.colors
    Column(modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 20.dp)) {
        Text(
            text = ui.title,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 2.dp),
            style = CassiniTheme.type.noteTitle,
            color = c.ink,
        )
        Text(
            text = Dates.dayTime(ui.recordedDay, ui.recordedAt) + " · " + Durations.clock(ui.durationMs),
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            style = CassiniTheme.type.caption,
            color = c.text2,
        )
    }
}
