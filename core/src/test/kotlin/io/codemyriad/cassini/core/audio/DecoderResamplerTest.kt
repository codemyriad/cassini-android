package io.codemyriad.cassini.core.audio

import io.codemyriad.cassini.core.audio.whistle.WhistleResampler
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DecoderResamplerTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun wavDecoderSelectsDesktopFirAndResumeReproducesTheSameTail() {
        val source = ShortArray(48001) { ((it * 193) % 16384 - 8192).toShort() }
        val header = ByteBuffer.allocate(44 + source.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + source.size * 2).put("WAVEfmt ".toByteArray())
        header.putInt(16).putShort(1).putShort(1).putInt(48000).putInt(96000).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(source.size * 2)
        for (sample in source) header.putShort(sample)
        val file = folder.newFile("speech.wav").also { it.writeBytes(header.array()) }
        val expected = ArrayList<Float>()
        val fir = WhistleResampler(48000) { samples, size -> expected.addAll(samples.take(size)) }
        fir.accept(FloatArray(source.size) { source[it] / 32768f })
        fir.finish()
        val included = RandomAccessFile(file, "r").use { WavDecoder.decodeWav(it, included = true).samples }
        val legacy = RandomAccessFile(file, "r").use { WavDecoder.decodeWav(it).samples }
        assertArrayEquals(expected.toFloatArray(), included, 0f)
        assertFalse(legacy.contentEquals(included))
        val resumed = ArrayList<Float>()
        RandomAccessFile(file, "r").use {
            WavDecoder.decodeWav(
                it,
                onSamples = { samples, size -> resumed.addAll(samples.take(size)) },
                retainPcm = false,
                startSample = 10000,
                included = true,
            )
        }
        assertArrayEquals(included.copyOfRange(10000, included.size), resumed.toFloatArray(), 0f)
    }
}
