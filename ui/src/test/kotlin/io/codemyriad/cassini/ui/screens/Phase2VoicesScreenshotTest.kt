package io.codemyriad.cassini.ui.screens

import io.codemyriad.cassini.ui.BoardTest
import io.codemyriad.cassini.ui.components.SheetBoardFrame
import io.codemyriad.cassini.ui.note.VoicesQuestionContent
import io.codemyriad.cassini.ui.theme.CassiniTheme
import org.junit.Test
import org.robolectric.annotation.Config

/** D103 (L-60) light/dark evidence for the voices question over the open meeting (board P2Voices-Phones). */
class Phase2VoicesScreenshotTest : BoardTest() {

    @Test fun voicesQuestionLight() = question(dark = false)

    @Test
    @Config(qualifiers = "+night")
    fun voicesQuestionDark() = question(dark = true)

    private fun question(dark: Boolean) {
        board(if (dark) "dark" else "light", "phase2-voices-question", dark) {
            SheetBoardFrame(color = CassiniTheme.colors.raised) { VoicesQuestionContent(onAnswer = {}) }
        }
    }
}
