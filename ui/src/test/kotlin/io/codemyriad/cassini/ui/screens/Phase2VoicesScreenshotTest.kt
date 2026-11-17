package io.codemyriad.cassini.ui.screens

import io.codemyriad.cassini.ui.BoardTest
import io.codemyriad.cassini.ui.fixtures.BoardFixtures
import io.codemyriad.cassini.ui.note.NoteScreen
import io.codemyriad.cassini.ui.note.NoteSheet
import org.junit.Test
import org.robolectric.annotation.Config

/** The real native question over the open meeting, including a retryable preference-write failure. */
class Phase2VoicesScreenshotTest : BoardTest() {
    @Test fun voicesQuestionLight() = question(dark = false)

    @Test fun voicesFailureLight() = question(dark = false, error = true)

    @Test
    @Config(qualifiers = "+night")
    fun voicesQuestionDark() = question(dark = true)

    @Test
    @Config(qualifiers = "+night")
    fun voicesFailureDark() = question(dark = true, error = true)

    private fun question(dark: Boolean, error: Boolean = false) {
        show(dark) {
            NoteScreen(BoardFixtures.readAndListen.copy(sheet = NoteSheet.VoicesQuestion(error = error)), {})
        }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(1_000)
        val name = if (error) "phase2-voices-save-error" else "phase2-voices-question"
        capture(if (dark) "dark" else "light", name, window = true)
    }
}
