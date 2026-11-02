package org.cassini.core.asr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/** D26.1: the 100 Hz high-pass on Silero's input, and its state carried from window to window of a track. */
class SileroInputFilterTest {
    private fun tone(hz: Double, n: Int, dc: Float = 0f) =
        FloatArray(n) { (0.01 * sin(2 * PI * hz * it / RATE)).toFloat() + dc }

    private fun rms(samples: FloatArray, from: Int) =
        sqrt(samples.drop(from).sumOf { it.toDouble() * it } / (samples.size - from))

    @Test fun theCoefficientsHaveADoubleZeroAtDc() {
        assertEquals(0.0, SileroInputFilter.B0 + SileroInputFilter.B1 + SileroInputFilter.B2, 0.0)
    }

    @Test fun aDcOffsetIsRemovedAndSpeechFrequenciesPass() {
        val n = RATE
        // The failing microphone of D26: -2060 LSB of DC under a quiet signal.
        val dc = -2060f / 32768
        val withDc = SileroInputFilter().process(tone(500.0, n, dc))
        val clean = SileroInputFilter().process(tone(500.0, n))
        val settled = RATE / 10
        assertTrue("the offset is gone", abs(withDc.drop(settled).average()) < 1e-6)
        for (i in settled until n) assertEquals("DC invariance at $i", clean[i], withDc[i], 1e-6f)
        assertEquals("500 Hz passes", rms(tone(500.0, n), settled), rms(clean, settled), 0.0002)
        val low = SileroInputFilter().process(tone(20.0, n))
        assertTrue("20 Hz is cut", rms(low, settled) < 0.1 * rms(tone(20.0, n), settled))
    }

    @Test fun aWindowStartsFromTheStateWhereThePreviousWindowRecordedIt() {
        val track = tone(300.0, 70_000, dc = -0.06f)
        val continuous = SileroInputFilter().process(track)
        val input = TrackSileroInput()
        // Two windows that overlap, as IncrementalTranscript cuts them: the second starts 4000 samples before the
        // first ends.
        val first = input.window(0, next = 36_000).process(track.copyOfRange(0, 40_000))
        val second = input.window(36_000, next = null).process(track.copyOfRange(36_000, 70_000))
        assertArrayEquals(continuous.copyOfRange(0, 40_000), first, 0f)
        assertArrayEquals("carried, not restarted", continuous.copyOfRange(36_000, 70_000), second, 0f)
    }

    @Test fun aWindowThatDoesNotFollowOnStartsFromZero() {
        val track = tone(300.0, 20_000, dc = -0.06f)
        val input = TrackSileroInput()
        input.window(0, next = 8_000).process(track.copyOfRange(0, 10_000))
        // After a resume from a checkpoint the next window starts elsewhere: zero state, as a fresh filter.
        val resumed = input.window(12_000, next = null).process(track.copyOfRange(12_000, 20_000))
        assertArrayEquals(SileroInputFilter().process(track.copyOfRange(12_000, 20_000)), resumed, 0f)
    }

    @Test fun tinyValuesAreFlushedToZero() {
        val out = SileroInputFilter().process(FloatArray(4) { 1e-30f })
        assertTrue(out.all { it == 0f })
    }

    private companion object {
        const val RATE = 16_000
    }
}
