package io.codemyriad.cassini.core.audio.whistle

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.random.Random

class WhistleResamplerStateTest {
    @Test fun checkpointDoesNotCreateANewFirEdgeOrLoseThePolyphaseHistory() {
        val input = FloatArray(8193) { Random(it).nextFloat() - 0.5f }
        val expected = ArrayList<Float>()
        WhistleResampler(48000) { data, count -> expected.addAll(data.take(count)) }.apply { accept(input); finish() }
        for (cut in listOf(1, 47, 48, 49, 257, 4097)) {
            val actual = ArrayList<Float>()
            val before = WhistleResampler(48000) { data, count -> actual.addAll(data.take(count)) }
            before.accept(input.copyOfRange(0, cut))
            val saved = before.snapshot()
            val after = WhistleResampler(48000) { data, count -> actual.addAll(data.take(count)) }
            after.restore(saved)
            saved.history.fill(Float.NaN) // A caller cannot change live restored history through its snapshot array.
            after.accept(input.copyOfRange(cut, input.size))
            after.finish()
            assertArrayEquals("cut=$cut", expected.toFloatArray(), actual.toFloatArray(), 0f)
        }
    }

    @Test fun invalidFirPhaseAndRateAreRejectedBeforeMutatingTheNewInstance() {
        val source = WhistleResampler(48000) { _, _ -> }
        source.accept(FloatArray(257))
        val state = source.snapshot()
        val target = WhistleResampler(48000) { _, _ -> }
        assertThrows(IllegalArgumentException::class.java) { target.restore(state.copy(next = state.next + 1)) }
        assertThrows(IllegalArgumentException::class.java) { target.restore(state.copy(rate = 16000)) }
        target.restore(state)
    }
}
