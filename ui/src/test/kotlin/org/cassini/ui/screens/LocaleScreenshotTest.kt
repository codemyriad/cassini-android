package org.cassini.ui.screens

import org.cassini.ui.BoardTest
import org.junit.Test
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Six boards in Italian (docs/design/ui.md section 10.2), goldens only in `it/`: the strings come from `values-it`
 * and the dates and numbers from the Italian locale. The fixtures' own content (titles, transcript words, names)
 * stays as the boards write it. The longer Italian copy must wrap, never clip.
 */
@Config(qualifiers = "+it")
class LocaleScreenshotTest : BoardTest() {
    override val locale: Locale = Locale.ITALIAN

    private fun italian(slug: String) = board("it", slug) { BoardContent(slug) }

    @Test fun library() = italian("00-library")

    @Test fun transcribing() = italian("02-transcribing")

    @Test fun readAndListen() = italian("03-read-and-listen")

    @Test fun nameSpeaker() = italian("04-name-a-speaker")

    @Test fun noteActions() = italian("06-note-actions-and-export")

    @Test fun settings() = italian("08-settings")
}
