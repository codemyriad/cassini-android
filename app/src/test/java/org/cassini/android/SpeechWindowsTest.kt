package org.cassini.android

import org.junit.Assert.*
import org.junit.Test

class SpeechWindowsTest {
    private val rates = listOf(16_000, 48_000)

    @Test fun defaultsMatchTheDesktopStockPolicy() {
        val p = DecodePolicy()
        assertEquals(DecodePolicy(10_000, 500, 500, 5_000, 500, 0, 0, false, true), p)
        val ref = DecodePolicy.PARAKEET_V3_REFERENCE
        assertTrue(ref.preserveSpan && !ref.syntheticPadding)
        assertEquals(listOf(30, 0, 0), listOf(ref.contextMs, ref.tailPaddingMs, ref.headPaddingMs))
        assertEquals(880_000, SpeechWindows.maxSafeSamples(16_000))
        assertEquals(2_640_000, SpeechWindows.maxSafeSamples(48_000))
    }

    @Test fun fixedWindowsHaveTheDocumentedShape() = rates.forEach { sr ->
        val total = 75 * sr
        val window = 15 * sr
        val overlap = sr / 2
        val stride = window - overlap
        val want = (0 until 5).map { Span(it * stride, it * stride + window) } + Span(5 * stride, total)
        val bounds = SpeechWindows.fixed(total, sr)
        assertEquals(want, bounds)
        for (i in 0 until bounds.lastIndex) {
            assertEquals(window, bounds[i].length)
            assertEquals(overlap, SpeechWindows.overlap(bounds[i], bounds[i + 1]))
        }
        assertEquals(listOf(Span(0, 8 * sr)), SpeechWindows.fixed(8 * sr, sr))
        assertEquals(emptyList<Span>(), SpeechWindows.fixed(0, sr))
    }

    @Test fun fixedWindowsLeaveNoGaps() = rates.forEach { sr ->
        for (total in listOf(1, sr, 14 * sr + 7, 15 * sr, 30 * sr, 75 * sr, 200 * sr)) {
            val bounds = SpeechWindows.fixed(total, sr)
            assertEquals(0, bounds.first().start)
            assertEquals(total, bounds.last().end)
            bounds.zipWithNext().forEach { (a, b) -> assertTrue("$sr $total $bounds", b.start <= a.end) }
        }
    }

    @Test fun ceilMsCoversEveryPaddedSample() {
        assertEquals(0L, SpeechWindows.ceilMs(0, 16_000))
        assertEquals(32L, SpeechWindows.ceilMs(511, 16_000))
        assertEquals(500L, SpeechWindows.ceilMs(8_000, 16_000))
        assertEquals(11L, SpeechWindows.ceilMs(511, 48_000))
        assertEquals(500L, SpeechWindows.ceilMs(24_000, 48_000))
        assertEquals(0L, SpeechWindows.ceilMs(511, 0))
    }

    @Test fun tailPaddingPadsEveryVADSpanAndShortNonVADDecodes() = rates.forEach { sr ->
        for (seconds in listOf(1, 9, 10, 15, 25, 55)) assertEquals(sr / 2, SpeechWindows.tailPadSamples(seconds * sr, sr, true))
        assertEquals(sr / 2, SpeechWindows.tailPadSamples(9 * sr, sr, false))
        assertEquals(sr / 2, SpeechWindows.tailPadSamples(10 * sr - 1, sr, false))
        assertEquals(0, SpeechWindows.tailPadSamples(10 * sr, sr, false))
        assertEquals(0, SpeechWindows.tailPadSamples(15 * sr, sr, false))
        assertEquals(0, SpeechWindows.tailPadSamples(0, sr, true))
        assertEquals(0, SpeechWindows.tailPadSamples(sr, 0, true))
    }

    @Test fun longVADSpanUsesOverlappingWindows() = rates.forEach { sr ->
        val total = 25 * sr + 700
        assertEquals(listOf(Span(0, 10 * sr), Span(9 * sr + sr / 2, 19 * sr + sr / 2), Span(19 * sr, total)), SpeechWindows.split(total, sr))
        assertEquals(listOf(Span(0, 10 * sr)), SpeechWindows.split(10 * sr, sr))
        assertEquals(listOf(Span(0, 10 * sr + 1)), SpeechWindows.split(10 * sr + 1, sr))
        assertEquals(listOf(Span(0, 10 * sr + sr / 2)), SpeechWindows.split(10 * sr + sr / 2, sr))
    }

    @Test fun shortTerminalWindowIsRebalanced() = rates.forEach { sr ->
        val tiny = 19 * sr + sr / 2 + 1
        assertEquals(listOf(Span(0, 10 * sr), Span(9 * sr + sr / 2, 15 * sr + 1), Span(14 * sr + sr / 2 + 1, tiny)), SpeechWindows.split(tiny, sr))
    }

    @Test fun sampledTotalsStayBoundedWithExactOverlap() = rates.forEach { sr ->
        for (total in listOf(10 * sr + sr / 2 + 1, 11 * sr, 19 * sr + sr / 2 + 1, 20 * sr, 25 * sr + 123, 55 * sr - 1)) {
            val bounds = SpeechWindows.split(total, sr)
            assertTrue("$sr $total", bounds.size >= 2)
            assertEquals(0, bounds.first().start)
            assertEquals(total, bounds.last().end)
            bounds.forEach { assertTrue("$sr $total $bounds", it.length in 5 * sr..10 * sr) }
            bounds.zipWithNext().forEach { (a, b) -> assertEquals("$sr $total", sr / 2, a.end - b.start) }
        }
    }

    @Test fun benchmarkPoliciesCoverTheSourceWithoutGaps() {
        for (sr in listOf(8_000, 16_000, 48_000)) for (seconds in listOf(5, 10, 15, 25)) for (overlapMs in listOf(0, 250, 500, 1000)) {
            val policy = DecodePolicy(windowMs = seconds * 1000, overlapMs = overlapMs, graceMs = 500, minTerminalMs = minOf(5, seconds / 2) * 1000)
            for (total in listOf(1, seconds * sr + sr / 2 + 1, 30 * sr + 1, 55 * sr)) {
                val bounds = SpeechWindows.split(total, sr, policy)
                assertTrue(bounds.isNotEmpty() && bounds.first().start == 0 && bounds.last().end == total)
                bounds.forEach { assertTrue("$bounds", it.start >= 0 && it.end <= total && it.length > 0 && it.length <= seconds * sr + sr / 2) }
                bounds.zipWithNext().forEach { (a, b) -> assertEquals("$bounds", overlapMs * sr / 1000, a.end - b.start) }
            }
        }
    }

    @Test fun preservedSpanStaysWhole() {
        for (sr in listOf(8_000, 16_000, 48_000)) {
            val whole = DecodePolicy(preserveSpan = true)
            for (total in listOf(1, 14 * sr + 1, 25 * sr + 60 * sr / 1000)) assertEquals(listOf(Span(0, total)), SpeechWindows.split(total, sr, whole))
            assertTrue(SpeechWindows.split(14 * sr, sr).size >= 2)
            assertEquals(emptyList<Span>(), SpeechWindows.split(0, sr, whole))
            assertEquals(1, SpeechWindows.split(24 * sr, sr, DecodePolicy.PARAKEET_V3_REFERENCE).size)
        }
    }

    @Test fun contextClipsToRealSamples() = rates.forEach { sr ->
        val k = sr / 16_000
        assertEquals(Span(0, 24_000 * k), SpeechWindows.context(0, 16_000 * k, 40_000 * k, sr, 500))
        assertEquals(Span(16_000 * k, 40_000 * k), SpeechWindows.context(24_000 * k, 40_000 * k, 40_000 * k, sr, 500))
        assertEquals(Span(5 * sr - 30 * sr / 1000, 6 * sr + 30 * sr / 1000), SpeechWindows.context(5 * sr, 6 * sr, 10 * sr, sr, 30))
    }

    @Test fun vadDecodesCarryOverlapAndPolicyPadding() = rates.forEach { sr ->
        val total = 25 * sr + 700
        val windows = SpeechWindows.windows(total, sr, true)
        assertEquals(SpeechWindows.split(total, sr), windows.map { it.span })
        assertEquals(listOf(0, sr / 2, sr / 2), windows.map { it.overlap })
        windows.forEach { assertEquals(listOf(Chunk(it.span, 0, sr / 2)), it.chunks) }
        assertEquals(listOf(Window(Span(0, 4 * sr), 0, listOf(Chunk(Span(0, 4 * sr), 0, sr / 2)))), SpeechWindows.windows(4 * sr, sr, true))
        assertEquals(emptyList<Window>(), SpeechWindows.windows(0, sr, true))
    }

    @Test fun emergencySplitCutsAt55SecondsWithoutOverlap() = rates.forEach { sr ->
        val safe = 55 * sr
        val dense = SpeechWindows.windows(120 * sr, sr, false).single()
        assertEquals(listOf(Chunk(Span(0, safe), 0, 0), Chunk(Span(safe, 2 * safe), 0, 0), Chunk(Span(2 * safe, 120 * sr), 0, 0)), dense.chunks)
        assertEquals(Chunk(Span(safe, 60 * sr), 0, sr / 2), SpeechWindows.windows(60 * sr, sr, false).single().chunks.last())
        val reference = SpeechWindows.windows(120 * sr, sr, true, DecodePolicy.PARAKEET_V3_REFERENCE).single()
        assertEquals(listOf(Span(0, safe), Span(safe, 2 * safe), Span(2 * safe, 120 * sr)), reference.chunks.map { it.span })
        assertTrue(reference.chunks.all { it.headPad == 0 && it.tailPad == 0 })
    }

    @Test fun referencePolicySkipsChunksWithoutTwoFeatureFrames() = rates.forEach { sr ->
        val ref = DecodePolicy.PARAKEET_V3_REFERENCE
        val frames = 20 * sr / 1000
        assertEquals(emptyList<Chunk>(), SpeechWindows.windows(frames - 1, sr, true, ref).single().chunks)
        assertEquals(listOf(Chunk(Span(0, frames), 0, 0)), SpeechWindows.windows(frames, sr, true, ref).single().chunks)
        assertEquals(listOf(Chunk(Span(55 * sr, 55 * sr + frames), 0, 0)), SpeechWindows.windows(55 * sr + frames, sr, false, ref).single().chunks.drop(1))
        assertEquals(1, SpeechWindows.windows(55 * sr + frames - 1, sr, false, ref).single().chunks.size)
        assertEquals(Chunk(Span(0, 1), 0, sr / 2), SpeechWindows.windows(1, sr, true).single().chunks.single())
    }

    /** Steady noise with [quiet] stretches of silence, given in milliseconds. */
    private fun noisy(seconds: Int, rate: Int, vararg quiet: IntRange): FloatArray {
        val random = java.util.Random(7)
        return FloatArray(seconds * rate) { i ->
            val ms = i * 1000L / rate
            if (quiet.any { ms in it }) 0f else (random.nextFloat() - .5f) * .4f
        }
    }

    @Test fun aSpanOfOrdinaryLengthStaysWhole() {
        for (rate in listOf(16000, 48000)) {
            val samples = noisy(28, rate)
            assertEquals(listOf(Span(0, samples.size)), SpeechWindows.quietPieces(samples, Span(0, samples.size), rate))
            assertEquals(listOf(Span(rate, 20 * rate)), SpeechWindows.quietPieces(samples, Span(rate, 20 * rate), rate))
        }
    }

    @Test fun aLongSpanIsCutWhereItIsQuiet() {
        for (rate in listOf(16000, 48000)) {
            // Pauses at 22.0 s and, 23.4 s after the first cut, at 45.5 s. The pause at 10 s is too early to be used.
            val samples = noisy(70, rate, 10_000..10_200, 22_000..22_100, 45_500..45_600)
            val pieces = SpeechWindows.quietPieces(samples, Span(0, samples.size), rate)
            assertEquals(3, pieces.size)
            assertEquals(0, pieces.first().start)
            assertEquals(samples.size, pieces.last().end)
            assertTrue("Pieces cover the span back to back", pieces.zipWithNext().all { (a, b) -> a.end == b.start })
            assertTrue("First cut inside the pause: ${pieces[0].end * 1000L / rate}", pieces[0].end * 1000L / rate in 22_000..22_100)
            assertTrue("Second cut inside the pause: ${pieces[1].end * 1000L / rate}", pieces[1].end * 1000L / rate in 45_500..45_600)
            assertTrue(pieces.dropLast(1).all { it.length in 20 * rate..25 * rate })
            assertTrue(pieces.last().length <= 28 * rate)
        }
    }

    @Test fun aSpanWithNoPauseIsStillCutBeforeADecodeRunsLong() {
        val rate = 16000
        val samples = FloatArray(180 * rate) { .2f }
        val pieces = SpeechWindows.quietPieces(samples, Span(3 * rate, samples.size), rate)
        assertTrue(pieces.size >= 7)
        assertEquals(3 * rate, pieces.first().start)
        assertEquals(samples.size, pieces.last().end)
        assertTrue(pieces.zipWithNext().all { (a, b) -> a.end == b.start })
        assertTrue(pieces.all { it.length <= 28 * rate && it.length > 3 * rate })
    }
}
