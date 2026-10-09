package org.cassini.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import org.cassini.ui.theme.CassiniTheme
import org.cassini.ui.theme.Glyph

/*
 * Content shown in the sheet, dialog and menu frames and in their real windows (OverlayScreenshotTest). The sheet is
 * board 06's Note actions copy; its own screen comes in P8.
 */

/** Board 06's sheet content: padding 10/12/20 (the handle holds the 10), title, meta and the rows. */
@Composable
internal fun NoteActionsContent() {
    val c = CassiniTheme.colors
    Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 20.dp)) {
        Text(
            text = "Call with supplier",
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 2.dp),
            style = CassiniTheme.type.noteTitle,
            color = c.ink,
        )
        Text(
            text = "Yesterday, 15:10 · 23:50 · 3 speakers",
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            style = CassiniTheme.type.caption,
            color = c.text2,
        )
        SheetRows()
    }
}

@Composable
private fun ColumnScope.SheetRows() {
    SheetSection("Save a copy")
    SheetActionRow(
        label = "Cassini document",
        glyph = Glyph.Music,
        onClick = {},
        extension = ".opus",
        subtitle = "Audio with the transcript inside. Plays anywhere.",
        style = SheetRowStyle.Featured,
    )
    SheetActionRow("Transcript as text", Glyph.TextFile, onClick = {}, extension = ".txt")
    SheetActionRow("Subtitles", Glyph.Subtitles, onClick = {}, extension = ".srt · .vtt")
    SheetDivider()
    SheetActionRow("Rename", Glyph.Pencil, onClick = {})
    SheetActionRow(
        label = "Transcribe again",
        glyph = Glyph.Retranscribe,
        onClick = {},
        subtitle = "Adds version 3. Speaker names carry over.",
    )
    SheetActionRow("Document info", Glyph.Info, onClick = {})
    SheetActionRow(
        label = "Move to trash",
        glyph = Glyph.Trash,
        onClick = {},
        subtitle = "Kept for 30 days, then deleted.",
        style = SheetRowStyle.Destructive,
    )
}

/** A destructive confirmation. */
@Composable
internal fun DeleteDialogContent() {
    CassiniDialogContent(
        title = "Delete for good?",
        body = "Its audio, transcript and voiceprints are deleted. This can’t be undone.",
        actions = { DialogActions("Delete", onConfirm = {}, onCancel = {}, destructive = true) },
    )
}

/** A dialog with a focused-looking text field and a disabled confirm. */
@Composable
internal fun RenameDialogContent() {
    CassiniDialogContent(
        title = "Rename",
        actions = { DialogActions("Save", onConfirm = {}, onCancel = {}, confirmEnabled = false) },
    ) {
        CassiniTextField("Call with supplier", onValueChange = {}, label = "Title", showFocus = true)
    }
}

/** Where the menu's options button sits in the menu tests: 48 dp at (326, 120). */
internal val MenuAnchor = DpRect(left = 326.dp, top = 120.dp, right = 374.dp, bottom = 168.dp)

/** The screen behind the menu: bg with the options button at [MenuAnchor]. */
@Composable
internal fun MenuScreen(menu: @Composable () -> Unit = {}) {
    Box(
        Modifier
            .fillMaxSize()
            .background(CassiniTheme.colors.bg),
    ) {
        Box(Modifier.offset(x = MenuAnchor.left, y = MenuAnchor.top)) {
            IconButton44(Glyph.MoreSmall, "Options for Anna Ricci", onClick = {}, iconSize = 20.dp, stroke = 0f)
            menu()
        }
    }
}

/** The person menu of Saved voices. */
@Composable
internal fun ColumnScope.PersonMenuItems() {
    CassiniMenuItem("Rename", onClick = {})
    CassiniMenuItem("Forget", onClick = {}, destructive = true)
}
