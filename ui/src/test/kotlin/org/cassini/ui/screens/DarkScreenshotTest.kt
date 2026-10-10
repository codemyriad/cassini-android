package org.cassini.ui.screens

import org.cassini.ui.BoardTest
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * The ten phone boards and the five States columns in the dark theme (docs/design/ui.md section 10.2), goldens only
 * in `dark/`. No board shows the dark theme: these wait for the owner's review (ui.md section 11, item 14).
 */
@Config(qualifiers = "+night")
class DarkScreenshotTest : BoardTest() {
    private fun dark(slug: String) = board("dark", slug, dark = true) { BoardContent(slug) }

    @Test fun library() = dark("00-library")

    @Test fun recording() = dark("01-recording")

    @Test fun transcribing() = dark("02-transcribing")

    @Test fun readAndListen() = dark("03-read-and-listen")

    @Test fun nameSpeaker() = dark("04-name-a-speaker")

    @Test fun modelDownload() = dark("05-model-download")

    @Test fun noteActions() = dark("06-note-actions-and-export")

    @Test fun savedVoices() = dark("07-saved-voices")

    @Test fun settings() = dark("08-settings")

    @Test fun trash() = dark("09-trash")

    @Config(qualifiers = StatesBoardTest.COLUMN_NIGHT)
    @Test
    fun statesTrust() = states("dark", 1, dark = true)

    @Config(qualifiers = StatesBoardTest.COLUMN_NIGHT)
    @Test
    fun statesRecording() = states("dark", 2, dark = true)

    @Config(qualifiers = StatesBoardTest.COLUMN_NIGHT)
    @Test
    fun statesTranscription() = states("dark", 3, dark = true)

    @Config(qualifiers = StatesBoardTest.COLUMN_NIGHT)
    @Test
    fun statesDownload() = states("dark", 4, dark = true)

    @Config(qualifiers = StatesBoardTest.COLUMN_NIGHT)
    @Test
    fun statesLibrary() = states("dark", 5, dark = true)
}
