package io.codemyriad.cassini.engine.whistle

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WhistleStreamTest {
    @Test
    fun invalidInputIsRefusedBeforeVadOrRecognition() {
        val stream = WhistleStream(16000, { error("VAD must not run") }, { error("ASR must not run") }) { _, _, _ -> }
        for (samples in listOf(FloatArray(8193), floatArrayOf(Float.NaN), floatArrayOf(Float.POSITIVE_INFINITY))) {
            assertThrows(IllegalArgumentException::class.java) { stream.accept(samples) }
        }
    }

    @Test
    fun rawSegmentsStayExactAcrossMultipleRingWraps() {
        var recognized = FloatArray(0)
        var segments = 0
        val stream = WhistleStream(16000, { 0.9f }, { recognized = it; byteArrayOf(7) }) { range, json, _ ->
            val expected = FloatArray((range.endSample - range.startSample).toInt()) { sample(range.startSample + it) }
            assertArrayEquals(expected, recognized, 0f)
            assertArrayEquals(byteArrayOf(7), json)
            segments++
        }
        var at = 0L
        val length = 1048701L
        while (at < length) {
            val count = minOf(8192L, length - at).toInt()
            stream.accept(FloatArray(count) { sample(at + it) })
            at += count
        }
        val summary = stream.finish()
        assertEquals(summary.frames, summary.voiced)
        assertTrue(segments >= 5)
    }

    private fun sample(index: Long) = ((index % 4093) - 2046).toFloat() / 4096f
}
