package io.codemyriad.cassini.ui.screens

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import io.codemyriad.cassini.ui.BoardTest
import io.codemyriad.cassini.ui.components.TranscriptFixtures
import io.codemyriad.cassini.ui.fixtures.BoardFixtures
import io.codemyriad.cassini.ui.note.NoteScreen
import io.codemyriad.cassini.ui.note.TranscriptMatch
import io.codemyriad.cassini.ui.note.TranscriptSearchUi
import kotlinx.collections.immutable.toImmutableList
import org.junit.Test
import org.robolectric.annotation.Config

/** Paired Linux evidence for native meeting find and scrolled states. Rest is the existing board03 pair. */
class Phase1MeetingScreenshotTest : BoardTest() {
    @Test fun scrolledLight() = meeting(dark = false, find = false)

    @Test
    @Config(qualifiers = "+night")
    fun scrolledDark() = meeting(dark = true, find = false)

    @Test fun findLight() = meeting(dark = false, find = true)

    @Test
    @Config(qualifiers = "+night")
    fun findDark() = meeting(dark = true, find = true)

    private fun meeting(dark: Boolean, find: Boolean) {
        val base = BoardFixtures.readAndListen
        val turns = if (find) {
            base.turns
        } else {
            (0 until 18).map { index ->
                base.turns[index % base.turns.size].copy(id = "scrolled-$index", startMs = index * 60_000L)
            }.toImmutableList()
        }
        val search = if (find) {
            val matches = turns.flatMap { turn ->
                TranscriptFixtures.matchesOf(turn.words, "delivery").map { TranscriptMatch(turn.id, it) }
            }.toImmutableList()
            TranscriptSearchUi("delivery", matches, 0)
        } else {
            null
        }
        val state = base.copy(
            turns = turns,
            activeWord = null,
            search = search,
            playback = base.playback?.copy(playing = false),
        )
        show(dark) { NoteScreen(state, onEvent = {}) }
        rule.mainClock.autoAdvance = true
        if (!find) rule.onNodeWithTag("transcript-list").performScrollToIndex(6)
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(1_000)
        capture(if (dark) "dark" else "light", if (find) "15-meeting-find" else "14-meeting-scrolled")
    }
}
