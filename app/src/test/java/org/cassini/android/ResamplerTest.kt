package org.cassini.android

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class ResamplerTest {
    private val rates = listOf(8000, 11025, 22050, 32000, 44100, 48000, 88200, 96000)
    private fun tone(hz: Double, rate: Int, seconds: Double = 1.0) =
        FloatArray((rate * seconds).toInt()) { (0.5 * sin(2 * PI * hz * it / rate)).toFloat() }
    /** RMS away from both edges, where the kernel is truncated. */
    private fun rms(samples: FloatArray, skip: Int = 400) =
        sqrt(samples.drop(skip).dropLast(skip).sumOf { it.toDouble() * it } / (samples.size - 2 * skip))

    @Test fun speechBandToneKeepsItsLevelAndPhase() {
        for (rate in rates) {
            val output = resampleTo16k(tone(1000.0, rate), rate)
            val gainDb = 20 * log10(rms(output) / (0.5 / sqrt(2.0)))
            assertEquals("$rate Hz gain", 0.0, gainDb, 0.1)
            for (j in 400 until output.size - 400) {
                assertEquals("$rate Hz sample $j", 0.5 * sin(2 * PI * 1000 * j / 16000), output[j].toDouble(), 0.01)
            }
        }
    }

    @Test fun tonesAboveEightKilohertzAreAttenuated() {
        for (rate in rates.filter { it > 16000 }) for (hz in listOf(8500.0, 10000.0, 15000.0, rate * 0.45).filter { it < rate / 2 }) {
            val level = rms(resampleTo16k(tone(hz, rate), rate)) / (0.5 / sqrt(2.0))
            assertTrue("$rate Hz input, $hz Hz tone at ${20 * log10(level)} dB", 20 * log10(level) < -35)
        }
    }

    @Test fun lengthIsTheRoundedRateRatio() {
        for (rate in rates) for (n in listOf(0, 1, 2, 3, 511, 512, 44099, 48000, 1_234_567)) {
            val output = resampleTo16k(FloatArray(n), rate)
            assertTrue("$rate Hz, $n samples -> ${output.size}", abs(output.size - n * 16000.0 / rate) <= 0.5)
        }
        val unchanged = FloatArray(1000)
        assertSame(unchanged, resampleTo16k(unchanged, 16000))
    }

    @Test fun constantSignalKeepsItsLevelUpToTheEdges() {
        for (rate in rates) {
            val output = resampleTo16k(FloatArray(rate / 10) { 0.25f }, rate)
            output.forEachIndexed { j, value -> assertEquals("$rate Hz sample $j", 0.25, value.toDouble(), 1e-5) }
        }
    }

    private fun streamed(input: FloatArray, rate: Int, random: kotlin.random.Random): FloatArray {
        val out = mutableListOf<Float>()
        val resampler = StreamingResampler(rate) { chunk, n -> repeat(n) { out += chunk[it] } }
        var at = 0
        while (at < input.size) {
            val n = minOf(input.size - at, random.nextInt(0, 5000))
            if (random.nextInt(8) == 0) resampler.push(FloatArray(n + 3).also { input.copyInto(it, 3, at, at + n) }, 3, n)
            else resampler.push(input.copyOfRange(at, at + n))
            at += n
        }
        val length = resampler.finish()
        assertEquals(length, out.size.toLong())
        assertEquals(length, resampler.produced)
        return out.toFloatArray()
    }

    @Test fun streamingInRandomBlocksEqualsWholeArray() {
        val random = kotlin.random.Random(7)
        for (rate in rates + 16000) for (n in listOf(0, 1, 37, 44100, 123_457)) {
            val input = FloatArray(n) { random.nextFloat() * 2 - 1 }
            val expected = resampleTo16k(input, rate)
            val actual = streamed(input, rate, random)
            assertEquals("$rate Hz, $n samples", expected.size, actual.size)
            for (j in expected.indices) assertEquals("$rate Hz sample $j", expected[j], actual[j], 1e-5f)
        }
    }

    @Test fun streamingSilenceMatchesZeros() {
        val out = mutableListOf<Float>()
        StreamingResampler(44100) { chunk, n -> repeat(n) { out += chunk[it] } }.apply {
            push(FloatArray(1000) { 0.25f }); silence(10_000); push(FloatArray(1000) { -0.25f }); finish()
        }
        val expected = resampleTo16k(FloatArray(1000) { 0.25f } + FloatArray(10_000) + FloatArray(1000) { -0.25f }, 44100)
        assertArrayEquals(expected, out.toFloatArray(), 1e-6f)
    }
}
