package io.codemyriad.cassini.core.asr.whistle

import org.junit.Assert.*
import org.junit.Test

class WhistleSpeechGateTest {
    @Test
    fun `two of three votes attack with capped pre-roll then close on nineteenth quiet frame`() {
        val probabilities = List(300) { 0f } + List(5) { 0.6f } + List(19) { 0f }
        val expected = listOf(WhistleSpeechSegment(292L * 512, 324L * 512, "silence"))
        for (chunk in listOf(1, 511, 512, 8192)) assertEquals(expected, run(probabilities, chunk))
    }

    @Test
    fun `attack and release thresholds include equality`() {
        val probabilities = listOf(0.5f, 0.5f) + List(20) { 0.35f }
        assertEquals(listOf(WhistleSpeechSegment(0, 22L * 512, "flush")), run(probabilities))
        assertTrue(run(listOf(0.5f, 0.49f, 0.49f)).isEmpty())
        assertEquals(listOf(WhistleSpeechSegment(0, 3L * 512, "flush")), run(listOf(0.5f, 0f, 0.5f)))
    }

    @Test
    fun `duration rules retain desktop frame rounding and reason priority`() {
        assertEquals(listOf(WhistleSpeechSegment(0, 469L * 512, "durationLimit")), run(List(469) { 1f }))
        assertEquals(
            listOf(WhistleSpeechSegment(0, 442L * 512, "durationPause")),
            run(List(438) { 1f } + List(4) { 0f }),
        )
        assertEquals(
            listOf(WhistleSpeechSegment(0, 438L * 512, "silence")),
            run(List(419) { 1f } + List(19) { 0f }),
        )
    }

    @Test
    fun `partial frames never reach VAD and only extend an active segment`() {
        var calls = 0
        val segments = mutableListOf<WhistleSpeechSegment>()
        val gate = WhistleSpeechGate({ calls++; 1f }, segments::add)
        gate.accept(FloatArray(1024 + 127))
        gate.finish()
        assertEquals(2, calls)
        assertEquals(listOf(WhistleSpeechSegment(0, 1151, "flush")), segments)
        assertTrue(run(emptyList(), tail = 511).isEmpty())
        assertTrue(run(listOf(1f), tail = 511).isEmpty())
    }

    @Test
    fun `complete frames keep exact order through arbitrary chunks and borrowed buffers`() {
        val input = FloatArray(4097) { it.toFloat() }
        var position = 0
        val gate = WhistleSpeechGate({ frame ->
            assertEquals(512, frame.size)
            frame.forEach { assertEquals((position++).toFloat(), it, 0f) }
            0f
        }, { error("Silence must not produce segments") })
        input.asList().chunked(511).forEach { gate.accept(it.toFloatArray()) }
        gate.finish()
        assertEquals(4096, position)
    }

    @Test
    fun `invalid probabilities and callback failures cannot silently become silence`() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, -0.01f, 1.01f)) {
            val gate = WhistleSpeechGate({ value }, { error("Invalid probability must not produce a segment") })
            assertThrows(IllegalArgumentException::class.java) { gate.accept(FloatArray(512)) }
            assertThrows(IllegalStateException::class.java) { gate.finish() }
        }
        val failed = WhistleSpeechGate({ error("model failure") }, { })
        assertThrows(IllegalStateException::class.java) { failed.accept(FloatArray(512)) }
        assertThrows(IllegalStateException::class.java) { failed.accept(FloatArray(512)) }
        val closed = WhistleSpeechGate({ 0f }, { })
        closed.finish()
        assertThrows(IllegalStateException::class.java) { closed.finish() }
    }

    private fun run(probabilities: List<Float>, chunk: Int = 8192, tail: Int = 0): List<WhistleSpeechSegment> {
        var frame = 0
        val segments = mutableListOf<WhistleSpeechSegment>()
        val gate = WhistleSpeechGate({ probabilities[frame++] }, segments::add)
        FloatArray(probabilities.size * 512 + tail).asList().chunked(chunk).forEach { gate.accept(it.toFloatArray()) }
        gate.finish()
        assertEquals(probabilities.size, frame)
        return segments
    }
}
