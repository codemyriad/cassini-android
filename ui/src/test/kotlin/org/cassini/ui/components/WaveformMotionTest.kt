package org.cassini.ui.components

import kotlinx.collections.immutable.toImmutableList
import org.cassini.ui.components.WaveformLevels.Companion.BARS
import org.cassini.ui.components.WaveformLevels.Companion.BAR_MS
import org.cassini.ui.components.WaveformLevels.Companion.SAMPLE_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scroll of a running [Waveform]. The tests give [WaveformMotion] frame times and samples, as the frame loop does,
 * and read the values that the draw phase reads.
 */
class WaveformMotionTest {
    private val motion = WaveformMotion()

    @Test fun theFirstFrameShowsTheStaticPicture() {
        val first = sample(START_MS)
        motion.step(first, 0)
        assertSame(first, motion.levels)
        assertEquals(0f, motion.offset, EXACT)
        assertEquals(first.bars.last(), motion.newest, EXACT)
        assertEquals(0f, motion.older, EXACT)
    }

    @Test fun thePlayheadWaitsForTheSecondSample() {
        motion.step(sample(START_MS), 0)
        frames(FRAME_MS, SAMPLE_MS) { motion.step(sample(START_MS), it) }
        assertEquals(0f, motion.offset, EXACT)
    }

    @Test fun theBarsNeverMoveBackAndStopAtTheLimit() {
        motion.step(sample(START_MS), 0)
        val late = sample(START_MS + SAMPLE_MS)
        // The bars shift by one when the second sample arrives. Then no sample arrives: the playhead runs to its
        // limit and waits there.
        motion.step(late, SAMPLE_MS * NANOS_PER_MS)
        var last = motion.offset
        frames(SAMPLE_MS + FRAME_MS, 6 * SAMPLE_MS) { nanos ->
            motion.step(late, nanos)
            assertTrue("The bars moved back at ${nanos / NANOS_PER_MS} ms", motion.offset <= last)
            last = motion.offset
        }
        val newestBar = late.capturedMs / BAR_MS
        val limit = (newestBar + 1 - (late.capturedMs + 2 * SAMPLE_MS).toDouble() / BAR_MS).toFloat()
        assertEquals(limit, motion.offset, CLOSE)
    }

    @Test fun aNewBarKeepsTheBarsInPlace() {
        motion.step(sample(START_MS), 0)
        val second = sample(START_MS + SAMPLE_MS)
        val lastFrame = frames(SAMPLE_MS, 2 * SAMPLE_MS) { motion.step(second, it) }
        val before = motion.offset
        // A sample two bars on, at the time of the last frame: the bars shift by two, and the picture stays.
        val third = sample(START_MS + SAMPLE_MS + 2 * BAR_MS)
        motion.step(third, lastFrame)
        assertSame(third, motion.levels)
        assertEquals(before + 2, motion.offset, CLOSE)
        assertEquals(second.bars[1], motion.older, EXACT)
        assertEquals(0f, motion.newest, EXACT)
    }

    @Test fun theNewestBarGrowsToItsLevel() {
        motion.step(sample(START_MS), 0)
        val next = sample(START_MS + BAR_MS)
        motion.step(next, FRAME_MS * NANOS_PER_MS)
        val first = motion.newest
        assertTrue("The newest bar did not start low", first < next.bars.last() / 2)
        frames(2 * FRAME_MS, 5 * SAMPLE_MS) { motion.step(next, it) }
        assertEquals(next.bars.last(), motion.newest, CLOSE)
    }

    @Test fun anEarlierCapturedTimeStartsAgain() {
        motion.step(sample(START_MS), 0)
        val second = sample(START_MS + SAMPLE_MS)
        val lastFrame = frames(SAMPLE_MS, 2 * SAMPLE_MS) { motion.step(second, it) }
        val again = sample(SAMPLE_MS)
        motion.step(again, lastFrame + FRAME_MS * NANOS_PER_MS)
        assertSame(again, motion.levels)
        assertEquals(0f, motion.offset, EXACT)
        assertEquals(again.bars.last(), motion.newest, EXACT)
        assertEquals(0f, motion.older, EXACT)
    }

    @Test fun stopShowsTheStaticPicture() {
        motion.step(sample(START_MS), 0)
        motion.stop()
        assertEquals(null, motion.levels)
    }

    /** Calls [frame] with the frame times from [fromMs] to [toMs], one frame each [FRAME_MS]; returns the last one. */
    private fun frames(fromMs: Long, toMs: Long, frame: (Long) -> Unit): Long {
        var nanos = 0L
        for (ms in fromMs..toMs step FRAME_MS) {
            nanos = ms * NANOS_PER_MS
            frame(nanos)
        }
        return nanos
    }

    /** Bars captured until [capturedMs]: each level differs, so a test can tell the bars apart. */
    private fun sample(capturedMs: Long): WaveformLevels {
        val bars = List(BARS) { i -> ((capturedMs / BAR_MS + i) % LEVELS + 1).toFloat() / (LEVELS + 1) }
        return WaveformLevels(bars.toImmutableList(), capturedMs)
    }

    private companion object {
        const val START_MS = 7_310L
        const val FRAME_MS = 8L
        const val NANOS_PER_MS = 1_000_000L
        const val LEVELS = 97
        const val EXACT = 0f
        const val CLOSE = 1e-3f
    }
}
