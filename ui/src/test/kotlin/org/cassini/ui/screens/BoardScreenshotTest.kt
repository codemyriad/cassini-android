package org.cassini.ui.screens

import org.cassini.ui.BoardTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The screens with the boards' content (docs/design/ui.md section 10.2), compared with design/reference/png by
 * tools/ui-fidelity/compare.py. Goldens in `light/`, named after the design slugs. [BoardContent] draws each board.
 */
class BoardScreenshotTest : BoardTest() {
    private fun light(slug: String) {
        board("light", slug) { BoardContent(slug) }
        assertEquals("Texts that do not fit", emptyList<String>(), cutTexts())
    }

    @Test fun library() = light("00-library")

    @Test fun recording() = light("01-recording")

    @Test fun transcribing() = light("02-transcribing")

    @Test fun readAndListen() = light("03-read-and-listen")

    @Test fun nameSpeaker() = light("04-name-a-speaker")

    @Test fun modelDownload() = light("05-model-download")

    @Test fun noteActions() = light("06-note-actions-and-export")

    @Test fun savedVoices() = light("07-saved-voices")

    @Test fun settings() = light("08-settings")

    @Test fun trash() = light("09-trash")
}
