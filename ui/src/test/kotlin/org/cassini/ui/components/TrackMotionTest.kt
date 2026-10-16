package org.cassini.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMotionTest {
    // 30 % done at 10 s; the window being decoded ends at 40 %; the whole audio takes 100 s.
    private val anchor = TrackAnchor(0.3f, 0.4f, 100_000f, 10_000)

    @Test
    fun thePredictionMovesAtThePaceAndStopsAtTheWindowEnd() {
        assertEquals(0.3f, anchor.predict(10_000), 1e-6f)
        assertEquals(0.35f, anchor.predict(15_000), 1e-6f)
        assertEquals(0.4f, anchor.predict(20_000), 1e-6f)
        assertEquals(0.4f, anchor.predict(60_000), 1e-6f)
    }

    @Test
    fun thePredictionNeverGoesBack() {
        // A clock before the completion keeps the done part.
        assertEquals(0.3f, anchor.predict(5_000), 1e-6f)
        var previous = 0f
        for (now in 0L..30_000L step 250) {
            val value = anchor.predict(now)
            assertTrue(value >= previous)
            previous = value
        }
        // A window end below the done part, or no pace, stays at the done part.
        assertEquals(0.3f, anchor.copy(windowEndFraction = 0.2f).predict(20_000), 1e-6f)
        assertEquals(0.3f, anchor.copy(msPerFraction = 0f).predict(20_000), 1e-6f)
    }

    @Test
    fun reachesAtMsInvertsThePrediction() {
        val at = anchor.reachesAtMs(0.35f)
        assertTrue(anchor.predict(at) >= 0.35f - 1e-6f)
        assertTrue(anchor.predict(at - 2) < 0.35f)
        assertEquals(10_000L, anchor.reachesAtMs(0.2f))
    }

    @Test
    fun theAimIsThePublishedValueOrAPredictionAheadOfIt() {
        assertEquals(0.62f, TrackMotion.aim(0.62f, null, 99_000), 1e-6f)
        assertEquals(0.35f, TrackMotion.aim(0.3f, anchor, 15_000), 1e-6f)
        assertEquals(0.5f, TrackMotion.aim(0.5f, anchor, 15_000), 1e-6f)
    }

    @Test
    fun theGlideMovesTowardTheAimWithoutPassingIt() {
        val first = TrackMotion.step(0.3f, 0.4f, 8)
        assertTrue(first > 0.3f && first < 0.4f)
        var shown = 0.3f
        repeat(500) { shown = TrackMotion.step(shown, 0.4f, 8) }
        assertEquals(0.4f, shown, 1e-6f)
    }

    @Test
    fun theGlideNeverGoesBackExceptForANewRun() {
        // A published value just below the prediction (rounding) holds the track.
        assertEquals(0.405f, TrackMotion.step(0.405f, 0.4f, 8), 1e-6f)
        assertEquals(0.405f, TrackMotion.step(0.405f, 0.405f, 8), 1e-6f)
        // A much lower value is a new run: the track goes there at once.
        assertEquals(0f, TrackMotion.step(0.4f, 0f, 8), 1e-6f)
    }

    @Test
    fun noTimeMeansNoMotion() {
        assertEquals(0.3f, TrackMotion.step(0.3f, 0.4f, 0), 1e-6f)
        assertEquals(0.3f, TrackMotion.step(0.3f, 0.4f, -5), 1e-6f)
    }
}
