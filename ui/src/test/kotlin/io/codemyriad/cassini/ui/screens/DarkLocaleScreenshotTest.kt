package io.codemyriad.cassini.ui.screens

import io.codemyriad.cassini.ui.BoardTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.annotation.Config
import java.util.Locale

/** Six Italian boards in dark, matching the existing light locale coverage. */
@Config(qualifiers = "+it-night")
class DarkLocaleScreenshotTest : BoardTest() {
    override val locale: Locale = Locale.ITALIAN

    private fun italian(slug: String) {
        board("it-dark", slug, dark = true) { BoardContent(slug) }
        assertEquals("Texts that do not fit", emptyList<String>(), cutTexts())
    }

    @Test fun library() = italian("00-library")

    @Test fun transcribing() = italian("02-transcribing")

    @Test fun readAndListen() = italian("03-read-and-listen")

    @Test fun nameSpeaker() = italian("04-name-a-speaker")

    @Test fun noteActions() = italian("06-note-actions-and-export")

    @Test fun settings() = italian("08-settings")
}
