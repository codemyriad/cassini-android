package org.cassini.ui.screens

import org.cassini.ui.BoardTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Every board in French, the longest of the six app languages (D50 point 6), goldens in `fr/`: the strings come
 * from `values-fr`, the dates and numbers from the French locale, and the fixtures' own content stays as the boards
 * write it. Longer copy must wrap, never be cut: [cutTexts] finds no text that does not fit its layout.
 */
@Config(qualifiers = "+fr")
class LongLanguageScreenshotTest : BoardTest() {
    override val locale: Locale = Locale.FRENCH

    private fun french(slug: String) {
        board("fr", slug) { BoardContent(slug) }
        assertEquals("Texts that do not fit", emptyList<String>(), cutTexts())
    }

    @Test fun library() = french("00-library")

    @Test fun recording() = french("01-recording")

    @Test fun transcribing() = french("02-transcribing")

    @Test fun readAndListen() = french("03-read-and-listen")

    @Test fun nameSpeaker() = french("04-name-a-speaker")

    @Test fun modelDownload() = french("05-model-download")

    @Test fun noteActions() = french("06-note-actions-and-export")

    @Test fun savedVoices() = french("07-saved-voices")

    @Test fun settings() = french("08-settings")

    @Test fun trash() = french("09-trash")
}
