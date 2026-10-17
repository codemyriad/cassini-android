package org.cassini.ui.note

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.cassini.ui.components.SpeakerNameUi
import org.cassini.ui.components.TurnUi
import org.cassini.ui.components.WordRef
import org.cassini.ui.components.WordUi
import org.cassini.ui.theme.SpeakerTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The frame clock of the active word: extrapolation, reconciliation of new anchors, resets, and the word lookup. */
class PlaybackClockTest {
    private fun ms(value: Long) = value * 1_000_000

    @Test fun aPlayingAnchorIsExtrapolatedInRealTime() {
        val clock = PlaybackClock()
        clock.update(PlaybackAnchor(1_000, ms(10_000), playing = true))
        assertEquals(1_000L, clock.positionMs(ms(10_000)))
        assertEquals(1_120L, clock.positionMs(ms(10_120)))
    }

    @Test fun theClockStopsAtTheEndOfTheFile() {
        val clock = PlaybackClock()
        clock.update(PlaybackAnchor(3_900, ms(0), playing = true, durationMs = 4_000))
        assertEquals(3_950L, clock.positionMs(ms(50)))
        assertEquals("not past the end before the completion", 4_000L, clock.positionMs(ms(500)))
        // A last tick a little behind: the hold stays at the end, not past it.
        clock.update(PlaybackAnchor(3_980, ms(500), playing = true, durationMs = 4_000))
        assertEquals(4_000L, clock.positionMs(ms(900)))
    }

    @Test fun aPausedAnchorStays() {
        val clock = PlaybackClock()
        clock.update(PlaybackAnchor(1_000, ms(10_000), playing = false))
        assertEquals(1_000L, clock.positionMs(ms(15_000)))
        assertEquals(0L, PlaybackClock().positionMs(ms(1)))
    }

    @Test fun anAnchorBehindTheClockNeverMovesItBack() {
        val clock = PlaybackClock()
        clock.update(PlaybackAnchor(1_000, ms(0), playing = true))
        assertEquals(1_150L, clock.positionMs(ms(150)))
        // The player reports 60 ms less than extrapolated: the clock holds or advances, and converges.
        clock.update(PlaybackAnchor(1_090, ms(150), playing = true))
        var last = 1_150L
        for (t in 151L..600L) {
            val position = clock.positionMs(ms(t))
            assertTrue("$position at $t after $last", position >= last)
            last = position
        }
        assertEquals("the difference is gone after the slew", 1_090L + 450, clock.positionMs(ms(600)))
    }

    @Test fun anAnchorAheadOfTheClockIsReachedSmoothly() {
        val clock = PlaybackClock()
        clock.update(PlaybackAnchor(1_000, ms(0), playing = true))
        assertEquals(1_150L, clock.positionMs(ms(150)))
        clock.update(PlaybackAnchor(1_200, ms(150), playing = true))
        assertEquals("no jump at the new sample", 1_150L, clock.positionMs(ms(150)))
        assertEquals(1_200L + 300, clock.positionMs(ms(450)))
    }

    @Test fun aSeekAPauseOrALargeDifferenceResetTheClock() {
        val clock = PlaybackClock()
        clock.update(PlaybackAnchor(5_000, ms(0), playing = true))
        assertEquals(5_100L, clock.positionMs(ms(100)))
        clock.update(PlaybackAnchor(1_000, ms(100), playing = true, epoch = 1))
        assertEquals("a seek goes back at once", 1_000L, clock.positionMs(ms(100)))
        clock.update(PlaybackAnchor(3_000, ms(200), playing = true, epoch = 1))
        assertEquals("a large difference snaps", 3_000L, clock.positionMs(ms(200)))
        clock.update(PlaybackAnchor(2_000, ms(300), playing = false, epoch = 1))
        assertEquals(2_000L, clock.positionMs(ms(400)))
        clock.update(PlaybackAnchor(2_000, ms(500), playing = true, epoch = 1))
        assertEquals("a pause resets the slew", 2_010L, clock.positionMs(ms(510)))
    }

    @Test fun theWordLookupFindsTheLastStartedWord() {
        val starts = WordStarts(
            persistentListOf(turn("w0", 0, 400, 400), turn("w3", 900), turn("w4")),
        )
        assertEquals(-1, starts.indexAt(-1))
        assertNull(starts.ref(-1))
        assertEquals(WordRef("w0", 0), starts.ref(starts.indexAt(0)))
        assertEquals(WordRef("w0", 0), starts.ref(starts.indexAt(399)))
        assertEquals("of equal starts the last plays", WordRef("w0", 2), starts.ref(starts.indexAt(400)))
        assertEquals(WordRef("w3", 0), starts.ref(starts.indexAt(5_000)))
        assertEquals(-1, WordStarts(persistentListOf()).indexAt(10))
    }

    private fun turn(id: String, vararg starts: Long) = TurnUi(
        id = id,
        speakerId = "S1",
        name = SpeakerNameUi.Placeholder(1),
        tone = SpeakerTone.Blue,
        startMs = starts.firstOrNull() ?: 0,
        words = starts.map { WordUi("w", it) }.toImmutableList(),
    )
}
