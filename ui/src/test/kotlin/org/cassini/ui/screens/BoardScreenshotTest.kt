package org.cassini.ui.screens

import org.cassini.ui.BoardTest
import org.cassini.ui.components.SheetBoardFrame
import org.cassini.ui.fixtures.BoardFixtures
import org.cassini.ui.library.LibraryScreen
import org.cassini.ui.models.ModelDownloadScreen
import org.cassini.ui.note.NameSpeakerSheetContent
import org.cassini.ui.note.NoteActionsSheetContent
import org.cassini.ui.note.NoteScreen
import org.cassini.ui.recording.RecordingScreen
import org.cassini.ui.settings.SettingsScreen
import org.cassini.ui.trash.TrashScreen
import org.cassini.ui.voices.SavedVoicesScreen
import org.junit.Test

/**
 * The screens with the boards' content (docs/design/ui.md section 10.2), compared with design/reference/png by
 * tools/ui-fidelity/compare.py. Goldens in `light/`, named after the design slugs. The sheets of boards 04 and 06
 * render inline in `SheetBoardFrame` over the boards' plain scrim.
 */
class BoardScreenshotTest : BoardTest() {
    @Test fun library() = board("light", "00-library") { LibraryScreen(BoardFixtures.library, onEvent = {}) }

    @Test fun recording() = board("light", "01-recording") {
        RecordingScreen(BoardFixtures.recording, onEvent = {})
    }

    @Test fun transcribing() = board("light", "02-transcribing") {
        NoteScreen(BoardFixtures.transcribing, onEvent = {})
    }

    @Test fun readAndListen() = board("light", "03-read-and-listen") {
        NoteScreen(BoardFixtures.readAndListen, onEvent = {})
    }

    @Test fun nameSpeaker() = board("light", "04-name-a-speaker") {
        SheetBoardFrame { NameSpeakerSheetContent(BoardFixtures.nameSpeaker, onEvent = {}) }
    }

    @Test fun modelDownload() = board("light", "05-model-download") {
        ModelDownloadScreen(BoardFixtures.modelDownload, onEvent = {})
    }

    @Test fun noteActions() = board("light", "06-note-actions-and-export") {
        SheetBoardFrame { NoteActionsSheetContent(BoardFixtures.noteActions, onAction = {}) }
    }

    @Test fun savedVoices() = board("light", "07-saved-voices") {
        SavedVoicesScreen(BoardFixtures.savedVoices, onEvent = {})
    }

    @Test fun settings() = board("light", "08-settings") { SettingsScreen(BoardFixtures.settings, onEvent = {}) }

    @Test fun trash() = board("light", "09-trash") { TrashScreen(BoardFixtures.trash, onEvent = {}) }
}
