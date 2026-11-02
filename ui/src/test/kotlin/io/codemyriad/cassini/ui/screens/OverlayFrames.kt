// Composables are nouns in PascalCase.
@file:Suppress("FunctionNaming")

package io.codemyriad.cassini.ui.screens

import androidx.compose.runtime.Composable
import io.codemyriad.cassini.ui.components.DialogBoardFrame
import io.codemyriad.cassini.ui.components.SheetBoardFrame
import io.codemyriad.cassini.ui.note.DocumentInfoDialogContent
import io.codemyriad.cassini.ui.note.MergeConfirmContent
import io.codemyriad.cassini.ui.note.MergeSheetContent
import io.codemyriad.cassini.ui.note.NameSpeakerSheetContent
import io.codemyriad.cassini.ui.note.NoteActionsSheetContent
import io.codemyriad.cassini.ui.note.NoteDialogContent
import io.codemyriad.cassini.ui.note.NoteSheet
import io.codemyriad.cassini.ui.note.NoteUiState
import io.codemyriad.cassini.ui.note.SubtitleFormatSheetContent
import io.codemyriad.cassini.ui.note.VersionsSheetContent

/**
 * The open overlay of a Note screen state drawn inline, as its window shows it (docs/design/ui.md section 10.1):
 * the dialog when one is open, else the sheet. Document info and the merge confirmation are dialogs. Like boards
 * 04 and 06, the frames show the plain scrim, not the screen under it.
 */
@Composable
internal fun NoteOverlayFrame(state: NoteUiState) {
    val dialog = state.dialog
    val sheet = state.sheet
    when {
        dialog != null -> DialogBoardFrame { NoteDialogContent(dialog, onEvent = {}) }
        sheet is NoteSheet.Info -> DialogBoardFrame { DocumentInfoDialogContent(sheet.ui, onDismiss = {}) }
        sheet is NoteSheet.Merge && sheet.ui.confirm != null -> DialogBoardFrame {
            MergeConfirmContent(sheet.ui, requireNotNull(sheet.ui.confirm), onEvent = {})
        }
        sheet != null -> SheetBoardFrame { NoteSheetContent(sheet) }
        else -> error("the state has no sheet or dialog")
    }
}

@Composable
private fun NoteSheetContent(sheet: NoteSheet) {
    when (sheet) {
        is NoteSheet.Actions -> NoteActionsSheetContent(sheet.ui, onAction = {})
        is NoteSheet.NameSpeaker -> NameSpeakerSheetContent(sheet.ui, onEvent = {})
        is NoteSheet.Merge -> MergeSheetContent(sheet.ui, onEvent = {})
        is NoteSheet.Versions -> VersionsSheetContent(sheet.ui, onPick = {})
        NoteSheet.SubtitleFormat -> SubtitleFormatSheetContent(onExport = {})
        is NoteSheet.Info -> error("Document info is a dialog")
    }
}
