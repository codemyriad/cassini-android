package io.codemyriad.cassini.core.audio.whistle

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class WhistleResamplerTest {
    @Test
    fun `streaming and tail bytes equal the independent desktop C reference`() {
        val golden = JSONObject(resource("fir-synthetic.json").toString(Charsets.UTF_8)).getJSONArray("cases")
        for (i in 0 until golden.length()) {
            val item = golden.getJSONObject(i)
            val count = item.getInt("inputSamples")
            val input = FloatArray(count) { ((it * 7919 + 12345) % 65536 - 32768) / 32768f }
            for (chunk in listOf(1, 2, 3, 47, 48, 49, 127, 512, 8192, maxOf(1, count)).distinct()) {
                val output = resample(input, chunk)
                assertEquals("$count/$chunk length", (count + 2) / 3, output.size)
                assertEquals("$count/$chunk bytes", item.getString("outputSha256"), sha(bytes(output)))
            }
        }
    }

    @Test
    fun `decoder boundaries must wait for all lookahead samples`() {
        val output = mutableListOf<Float>()
        val filter = WhistleResampler(48000) { data, count -> output.addAll(data.take(count)) }
        filter.accept(FloatArray(47))
        filter.accept(FloatArray(1))
        assertTrue("48 samples cannot supply index 48", output.isEmpty())
        filter.accept(floatArrayOf(1f))
        assertEquals(1, output.size)
        assertEquals(8.404098480241373e-06f.toRawBits(), output.single().toRawBits())
        filter.finish()
    }

    @Test
    fun `published coefficient metadata stays pinned`() {
        assertEquals(
            "52423b160d7d18e1c535a0750cc615e7df27c33f9e56f0f2b6bd7f27fd204e74",
            sha(resource("fir-48k-16k.json")),
        )
    }

    @Test
    fun `16 kHz passes exact bits through and honors the valid count`() {
        val input = floatArrayOf(-0f, Float.MIN_VALUE, 0.5f, Float.NaN)
        val output = mutableListOf<Float>()
        val filter = WhistleResampler(16000) { data, n -> output.addAll(data.take(n)) }
        filter.accept(input, 3)
        filter.finish()
        assertArrayEquals(input.take(3).map { it.toRawBits() }.toIntArray(), output.map { it.toRawBits() }.toIntArray())
    }

    @Test
    fun `invalid rates and use after finish are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { WhistleResampler(44100) { _, _ -> } }
        val filter = WhistleResampler(48000) { _, _ -> }
        assertThrows(IllegalArgumentException::class.java) { filter.accept(FloatArray(1), 2) }
        filter.finish()
        assertThrows(IllegalStateException::class.java) { filter.accept(FloatArray(0)) }
        assertThrows(IllegalStateException::class.java) { filter.finish() }
    }

    @Test
    fun `sink failure cannot resume a partially consumed stream`() {
        val filter = WhistleResampler(48000) { _, _ -> error("test sink failure") }
        assertThrows(IllegalStateException::class.java) { filter.accept(FloatArray(100)) }
        assertThrows(IllegalStateException::class.java) { filter.accept(FloatArray(100)) }
        assertThrows(IllegalStateException::class.java) { filter.finish() }
    }

    companion object {
        fun resample(input: FloatArray, chunk: Int): FloatArray {
            val out = ArrayList<Float>()
            val filter = WhistleResampler(48000) { data, count -> out.addAll(data.take(count)) }
            input.asList().chunked(chunk).forEach { filter.accept(it.toFloatArray()) }
            filter.finish()
            return out.toFloatArray()
        }

        fun bytes(samples: FloatArray): ByteArray {
            val buffer = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in samples) buffer.putFloat(sample)
            return buffer.array()
        }

        fun sha(data: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(data)
            return digest.joinToString("") { "%02x".format(it) }
        }

        private fun resource(name: String): ByteArray =
            requireNotNull(javaClass.getResourceAsStream("/whistle/$name")).use { it.readBytes() }
    }
}
