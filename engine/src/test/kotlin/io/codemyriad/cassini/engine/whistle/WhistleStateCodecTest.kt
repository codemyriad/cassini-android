package io.codemyriad.cassini.engine.whistle

import io.codemyriad.cassini.core.asr.whistle.WhistleSpeechGate
import io.codemyriad.cassini.core.audio.whistle.WhistleResampler
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException

class WhistleStateCodecTest {
    private fun state() = WhistleStream.State(
        16000, 777, 0, FloatArray(777) { it / 1024f }, doubleArrayOf(0.0, -0.0, 0.0123, -0.2),
        WhistleSpeechGate.State(FloatArray(512) { -it / 1024f }, booleanArrayOf(true, false, false), 265, 512, 1, 1, false, 0, 0, 0),
        WhistleResampler.State(16000, FloatArray(128), 0, 0), FloatArray(320) { it / 2048f }, 1, 1, 0.8f,
    )
    private fun encoded() = ByteArrayOutputStream().also { WhistleStateCodec.write(it, state()) }.toByteArray()

    @Test fun sidecarPreservesEveryPendingSampleAndNativeStateBitWithoutLoadingANeuralLibrary() {
        val expected = state()
        val decoded = WhistleStateCodec.read(ByteArrayInputStream(encoded()))
        assertEquals(expected.count, decoded.count)
        assertArrayEquals(expected.raw, decoded.raw, 0f)
        assertArrayEquals(expected.filter, decoded.filter, 0.0)
        assertArrayEquals(expected.gate.frame, decoded.gate.frame, 0f)
        assertArrayEquals(expected.gate.votes, decoded.gate.votes)
        assertArrayEquals(expected.vad, decoded.vad, 0f)
        assertEquals(expected.gate.used, decoded.gate.used)
        assertEquals(expected.filter[1].toRawBits(), decoded.filter[1].toRawBits())
    }

    @Test fun wrongSchemaOrModelPipelinePinsAndTrailingOrTruncatedBytesAreRefused() {
        for (offset in listOf(0, 4, 25)) {
            val bad = encoded().also { it[offset] = (it[offset].toInt() xor 1).toByte() }
            assertThrows(IllegalArgumentException::class.java) { WhistleStateCodec.read(ByteArrayInputStream(bad)) }
        }
        assertThrows(IllegalArgumentException::class.java) { WhistleStateCodec.read(ByteArrayInputStream(encoded() + byteArrayOf(0))) }
        assertThrows(EOFException::class.java) { WhistleStateCodec.read(ByteArrayInputStream(encoded().dropLast(1).toByteArray())) }
    }

    @Test fun invalidStateNeverProducesACheckpoint() {
        val output = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) {
            WhistleStateCodec.write(output, state().copy(vad = FloatArray(320) { Float.NaN }))
        }
        assertEquals(0, output.size())
        assertThrows(IllegalArgumentException::class.java) {
            WhistleStateCodec.write(output, state().copy(rawFirst = 1, raw = FloatArray(776)))
        }
    }
}
