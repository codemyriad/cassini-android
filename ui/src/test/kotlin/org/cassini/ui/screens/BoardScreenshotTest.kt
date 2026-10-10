package org.cassini.ui.screens

import org.cassini.ui.BoardTest
import org.cassini.ui.fixtures.BoardFixtures
import org.cassini.ui.library.LibraryScreen
import org.cassini.ui.note.NoteScreen
import org.cassini.ui.recording.RecordingScreen
import org.junit.Test

/**
 * The screens with the boards' content (docs/design/ui.md section 10.2), compared with design/reference/png by
 * tools/ui-fidelity/compare.py. Goldens in `light/`, named after the design slugs.
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
}
