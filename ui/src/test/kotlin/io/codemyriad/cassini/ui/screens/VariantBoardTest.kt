package io.codemyriad.cassini.ui.screens

import io.codemyriad.cassini.ui.BoardTest
import io.codemyriad.cassini.ui.components.DialogBoardFrame
import io.codemyriad.cassini.ui.components.SheetBoardFrame
import io.codemyriad.cassini.ui.fixtures.VariantFixtures
import io.codemyriad.cassini.ui.library.LibraryScreen
import io.codemyriad.cassini.ui.models.ModelDownloadScreen
import io.codemyriad.cassini.ui.note.MergeConfirmContent
import io.codemyriad.cassini.ui.note.NameSpeakerSheetContent
import io.codemyriad.cassini.ui.note.NoteActionsSheetContent
import io.codemyriad.cassini.ui.note.NoteScreen
import io.codemyriad.cassini.ui.recording.RecordingScreen
import io.codemyriad.cassini.ui.settings.LanguageChoice
import io.codemyriad.cassini.ui.settings.LanguageSheetContent
import io.codemyriad.cassini.ui.settings.SettingsScreen
import io.codemyriad.cassini.ui.trash.TrashDialogContent
import io.codemyriad.cassini.ui.trash.TrashScreen
import io.codemyriad.cassini.ui.voices.SavedVoicesDialogContent
import io.codemyriad.cassini.ui.voices.SavedVoicesScreen

/** Shared rendering only: subclasses declare their own tests, so dark does not inherit all light cases. */
abstract class VariantBoardTest : BoardTest() {
    protected open val variantDirectory: String = "variants"
    protected open val variantDark: Boolean = false

    protected fun library(name: String) = board(variantDirectory, name, dark = variantDark) {
        LibraryScreen(VariantFixtures.library.getValue(name), onEvent = {})
    }

    protected fun recording(name: String) = board(variantDirectory, name, dark = variantDark) {
        RecordingScreen(VariantFixtures.recording.getValue(name), onEvent = {})
    }

    protected fun note(name: String) = board(variantDirectory, name, dark = variantDark) {
        NoteScreen(VariantFixtures.transcribing.getValue(name), onEvent = {})
    }

    protected fun read(name: String) = board(variantDirectory, name, dark = variantDark) {
        NoteScreen(VariantFixtures.read.getValue(name), onEvent = {})
    }

    protected fun readOverlay(name: String) = board(variantDirectory, name, dark = variantDark) {
        NoteOverlayFrame(VariantFixtures.read.getValue(name))
    }

    protected fun noteSheet(name: String) = board(variantDirectory, name, dark = variantDark) {
        NoteOverlayFrame(VariantFixtures.noteSheets.getValue(name))
    }

    protected fun nameSheet(name: String) = board(variantDirectory, name, dark = variantDark) {
        SheetBoardFrame { NameSpeakerSheetContent(VariantFixtures.nameSheet.getValue(name), onEvent = {}) }
    }

    protected fun actions(name: String) = board(variantDirectory, name, dark = variantDark) {
        SheetBoardFrame { NoteActionsSheetContent(VariantFixtures.noteActions.getValue(name), onAction = {}) }
    }

    protected fun mergeConfirm(name: String) = board(variantDirectory, name, dark = variantDark) {
        val merge = VariantFixtures.merges.getValue(name)
        DialogBoardFrame { MergeConfirmContent(merge, requireNotNull(merge.confirm), onEvent = {}) }
    }

    protected fun models(name: String) = board(variantDirectory, name, dark = variantDark) {
        ModelDownloadScreen(VariantFixtures.modelDownload.getValue(name), onEvent = {})
    }

    protected fun settings(name: String) = board(variantDirectory, name, dark = variantDark) {
        SettingsScreen(VariantFixtures.settings.getValue(name), onEvent = {})
    }

    protected fun voices(name: String) = board(variantDirectory, name, dark = variantDark) {
        SavedVoicesScreen(VariantFixtures.savedVoices.getValue(name), onEvent = {})
    }

    protected fun trash(name: String) = board(variantDirectory, name, dark = variantDark) {
        TrashScreen(VariantFixtures.trash.getValue(name), onEvent = {})
    }

    protected fun trashDialog(name: String) = board(variantDirectory, name, dark = variantDark) {
        val dialog = requireNotNull(VariantFixtures.trash.getValue(name).dialog)
        DialogBoardFrame { TrashDialogContent(dialog, onEvent = {}) }
    }

    protected fun voicesDialog(name: String) = board(variantDirectory, name, dark = variantDark) {
        val dialog = requireNotNull(VariantFixtures.savedVoices.getValue(name).dialog)
        DialogBoardFrame { SavedVoicesDialogContent(dialog, onEvent = {}) }
    }

    protected fun languageSheet() = board(variantDirectory, "settings-language-sheet", dark = variantDark) {
        SheetBoardFrame { LanguageSheetContent(LanguageChoice.ITALIAN, onPick = {}) }
    }
}
