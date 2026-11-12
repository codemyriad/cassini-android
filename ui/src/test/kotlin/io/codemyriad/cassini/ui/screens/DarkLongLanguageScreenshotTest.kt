package io.codemyriad.cassini.ui.screens

import io.codemyriad.cassini.ui.BoardTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.annotation.Config
import java.util.Locale

/** Every existing French board in dark; long translations must fit in both modes. */
@Config(qualifiers = "+fr-night")
class DarkLongLanguageScreenshotTest : BoardTest() {
    override val locale: Locale = Locale.FRENCH

    private fun french(slug: String) {
        board("fr-dark", slug, dark = true) { BoardContent(slug) }
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
