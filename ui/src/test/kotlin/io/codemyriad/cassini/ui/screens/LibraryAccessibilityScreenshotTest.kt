package io.codemyriad.cassini.ui.screens

import io.codemyriad.cassini.ui.BoardTest
import io.codemyriad.cassini.ui.components.NoteStatusUi
import io.codemyriad.cassini.ui.fixtures.BoardFixtures
import io.codemyriad.cassini.ui.format.DayLabel
import io.codemyriad.cassini.ui.library.DaySection
import io.codemyriad.cassini.ui.library.LibraryScreen
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** Narrow-phone evidence for full status labels and separate recovery controls at larger text sizes. */
@Config(qualifiers = "w320dp-h844dp-xhdpi", fontScale = 1.5f)
class LibraryAccessibilityScreenshotTest : BoardTest() {
    override val fontScale = 1.5f

    @Test fun light() = library(dark = false)

    @Test
    @Config(qualifiers = "+night")
    fun dark() = library(dark = true)

    private fun library(dark: Boolean) {
        val base = BoardFixtures.supplier.copy(day = null)
        val rows = persistentListOf(
            base.copy(id = "finishing", status = NoteStatusUi.Transcribing, transcriptionMinutes = 0),
            base.copy(id = "paused", status = NoteStatusUi.Paused(60_000)),
            base.copy(id = "failed", status = NoteStatusUi.Failed),
        )
        board(if (dark) "dark" else "light", "18-library-large-text", dark) {
            LibraryScreen(
                BoardFixtures.library.copy(sections = persistentListOf(DaySection(DayLabel.Today, rows))),
                onEvent = {},
            )
        }
        val clippedStatuses = cutTexts().filter {
            it.startsWith("Transcribing") || it.startsWith("Paused at") ||
                it.startsWith("Couldn’t transcribe") || it.startsWith("Resume") || it.startsWith("Try again")
        }
        assertTrue("Status and recovery copy must fit: $clippedStatuses", clippedStatuses.isEmpty())
    }
}
