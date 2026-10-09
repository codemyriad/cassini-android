package org.cassini.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.cassini.ui.BoardTest
import org.cassini.ui.theme.CassiniTheme
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * Sheets, dialogs and menus (docs/design/ui.md section 10.1). The frames draw the content inline at the phone's size
 * (components/{sheet,dialogs,menu}-{light,dark}.png); the window tests show the same content in a real
 * `ModalBottomSheet`, `BasicAlertDialog` and `DropdownMenu`, captured with every window
 * (components/window-{sheet,dialog,menu}.png), to check that the frames place the content as the windows do.
 */
class OverlayScreenshotTest : BoardTest() {
    @Test fun sheetLight() = board("components", "sheet-light") { SheetBoardFrame { NoteActionsContent() } }

    @Test
    @Config(qualifiers = "+night")
    fun sheetDark() = board("components", "sheet-dark", dark = true) { SheetBoardFrame { NoteActionsContent() } }

    @Test
    @Config(qualifiers = "+h1688dp")
    fun dialogsLight() = board("components", "dialogs-light") { Dialogs() }

    @Test
    @Config(qualifiers = "+h1688dp-night")
    fun dialogsDark() = board("components", "dialogs-dark", dark = true) { Dialogs() }

    @Test fun menuLight() = board("components", "menu-light") { PersonMenuFrame() }

    @Test
    @Config(qualifiers = "+night")
    fun menuDark() = board("components", "menu-dark", dark = true) { PersonMenuFrame() }

    @Test fun windowSheet() = board("components", "window-sheet", window = true) {
        Background()
        CassiniSheet(onDismissRequest = {}) { NoteActionsContent() }
    }

    @Test fun windowDialog() = board("components", "window-dialog", window = true) {
        Background()
        CassiniDialog(
            onDismissRequest = {},
            title = "Delete for good?",
            body = "Its audio, transcript and voiceprints are deleted. This can’t be undone.",
            actions = { DialogActions("Delete", onConfirm = {}, onCancel = {}, destructive = true) },
        )
    }

    @Test fun windowMenu() = board("components", "window-menu", window = true) {
        MenuScreen {
            CassiniMenu(expanded = true, onDismissRequest = {}) { PersonMenuItems() }
        }
    }

    /** The destructive dialog over a phone, then the text-field dialog over another. */
    @Composable
    private fun Dialogs() {
        Column {
            Box(Modifier.size(390.dp, 844.dp)) { DialogBoardFrame { DeleteDialogContent() } }
            Box(Modifier.size(390.dp, 844.dp)) { DialogBoardFrame { RenameDialogContent() } }
        }
    }

    @Composable
    private fun PersonMenuFrame() {
        MenuBoardFrame(anchor = MenuAnchor, screen = { MenuScreen() }) { PersonMenuItems() }
    }

    @Composable
    private fun Background() {
        Box(
            Modifier
                .fillMaxSize()
                .background(CassiniTheme.colors.bg),
        )
    }
}
