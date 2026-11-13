package io.codemyriad.cassini.ui.screens

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import io.codemyriad.cassini.ui.BoardTest
import io.codemyriad.cassini.ui.fixtures.BoardFixtures
import io.codemyriad.cassini.ui.note.NoteScreen
import kotlinx.collections.immutable.toImmutableList
import org.junit.Test
import org.robolectric.annotation.Config

/** Linux-only paired scrolled and native details-sheet evidence; the rest state is board02. */
class Phase1TranscribingScreenshotTest : BoardTest() {
    @Test fun scrolledLight() = transcribing(dark = false, details = false)

    @Test
    @Config(qualifiers = "+night")
    fun scrolledDark() = transcribing(dark = true, details = false)

    @Test fun detailsLight() = transcribing(dark = false, details = true)

    @Test
    @Config(qualifiers = "+night")
    fun detailsDark() = transcribing(dark = true, details = true)

    private fun transcribing(dark: Boolean, details: Boolean) {
        val base = BoardFixtures.transcribing
        val state = if (details) {
            base
        } else {
            base.copy(
                turns = (0 until 18).map { index ->
                    base.turns[index % base.turns.size].copy(id = "scrolled-$index", startMs = index * 60_000L)
                }.toImmutableList(),
            )
        }
        show(dark) { NoteScreen(state, onEvent = {}) }
        rule.mainClock.autoAdvance = true
        if (details) {
            rule.onNodeWithTag("transcribing-details").performClick()
        } else {
            rule.onNodeWithTag("transcribing-draft").performScrollToIndex(6)
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(1_000)
        capture(
            if (dark) "dark" else "light",
            if (details) "17-transcribing-details" else "16-transcribing-scrolled",
            window = details,
        )
    }
}
