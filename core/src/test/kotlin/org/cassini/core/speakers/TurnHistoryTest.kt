package org.cassini.core.speakers

import org.junit.Assert.assertEquals
import org.junit.Test

class TurnHistoryTest {
    @Test fun theFirstWindowIsKeptOnTheRecordingClock() {
        val history = TurnHistory()
        history.add(listOf(SpeakerTurn(500, 9000, 0), SpeakerTurn(9000, 27000, 1)), 0)
        assertEquals(listOf(SpeakerTurn(500, 9000, 0), SpeakerTurn(9000, 27000, 1)), history.turns)
    }

    @Test fun aLaterWindowReplacesTurnsFromOneSecondIntoIt() {
        val history = TurnHistory(
            listOf(SpeakerTurn(0, 10_000, 0), SpeakerTurn(20_000, 28_000, 1), SpeakerTurn(27_500, 28_000, 0))
        )
        // The window starts at 26 s; its turns are relative to that start.
        history.add(listOf(SpeakerTurn(0, 5000, 1), SpeakerTurn(5000, 9000, 0)), 26_000)
        assertEquals(
            listOf(
                SpeakerTurn(0, 10_000, 0),
                SpeakerTurn(20_000, 27_000, 1),
                SpeakerTurn(27_000, 31_000, 1),
                SpeakerTurn(31_000, 35_000, 0),
            ),
            history.turns,
        )
    }

    @Test fun turnsThatEndBeforeTheCutAreDropped() {
        val history = TurnHistory()
        history.add(listOf(SpeakerTurn(0, 800, 0), SpeakerTurn(800, 3000, 1)), 26_000)
        assertEquals(listOf(SpeakerTurn(27_000, 29_000, 1)), history.turns)
    }
}
