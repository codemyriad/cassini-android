package org.cassini.core.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgressPaceTest {
    @Test fun noAnchorUntilTwoWindowsCompleted() {
        val pace = ProgressPace()
        assertNull(pace.anchor(260_000, 28_000, 0.99f))
        // The first window includes the model load: it gives no pace.
        pace.complete(28_000, 30_000)
        assertNull(pace.anchor(260_000, 54_000, 0.99f))
        pace.complete(54_000, 35_200)
        val anchor = pace.anchor(260_000, 80_000, 0.99f)!!
        assertEquals(54_000f / 260_000, anchor.doneFraction, 1e-6f)
        assertEquals(80_000f / 260_000, anchor.windowEndFraction, 1e-6f)
        // 5.2 s for 26 s of audio: 0.2 ms per ms, 52 s for the whole audio.
        assertEquals(52_000f, anchor.msPerFraction, 1f)
        assertEquals(35_200L, anchor.completedAtMs)
    }

    @Test fun thePaceIsSmoothed() {
        val pace = ProgressPace()
        pace.complete(26_000, 10_000)
        pace.complete(52_000, 15_200)
        pace.complete(78_000, 25_600)
        // 0.2 then 0.4 ms per ms: 0.8 * 0.2 + 0.2 * 0.4 = 0.24.
        assertEquals(0.24f * 100_000, pace.anchor(100_000, 100_000, 1f)!!.msPerFraction, 1f)
    }

    @Test fun noAnchorWhenTheWindowDoesNotGoPastTheDecodedAudio() {
        val pace = ProgressPace()
        pace.complete(26_000, 10_000)
        pace.complete(52_000, 15_000)
        assertNull(pace.anchor(100_000, 52_000, 0.99f))
        assertNull(pace.anchor(0, 78_000, 0.99f))
    }

    @Test fun theFractionsStayUnderTheCap() {
        val pace = ProgressPace()
        pace.complete(26_000, 10_000)
        pace.complete(98_000, 15_000)
        val anchor = pace.anchor(100_000, 100_000, 0.99f)!!
        assertEquals(0.98f, anchor.doneFraction, 1e-6f)
        assertEquals(0.99f, anchor.windowEndFraction, 1e-6f)
    }

    @Test fun anOlderOrEqualCompletionChangesNothing() {
        val pace = ProgressPace()
        pace.complete(26_000, 10_000)
        pace.complete(52_000, 15_000)
        val before = pace.anchor(100_000, 78_000, 0.99f)
        pace.complete(52_000, 20_000)
        pace.complete(26_000, 21_000)
        assertEquals(before, pace.anchor(100_000, 78_000, 0.99f))
    }
}
