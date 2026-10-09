package org.cassini.android

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PcmFileTest {
    @Test fun diskAudioKeepsTheClockAndReadsAcrossIoBoundaries() {
        val file = File.createTempFile("pcm-test", ".pcm")
        PcmFile(file).use { audio ->
            val block = FloatArray(4096) { it / 4096f }
            repeat(1000) { audio.append(block, block.size) }
            assertEquals(4_096_000, audio.sampleCount)
            assertEquals(256_000L, audio.durationMs)
            assertEquals(audio.sampleCount * 4L, file.length())
            val read = audio.read(4090, 16000)
            assertArrayEquals(FloatArray(16000) { block[(4090 + it) % block.size] }, read, 0f)
            // Reusing the decoder's borrowed array cannot change previously written samples.
            block.fill(-1f)
            assertEquals(0f, audio.read(0, 1)[0], 0f)
        }
        assertFalse(file.exists())
    }

    @Test fun readsAreBoundedAndFailureDeletesTemporaryAudio() {
        val file = File.createTempFile("pcm-test", ".pcm")
        try {
            PcmFile(file).use { audio ->
                audio.append(FloatArray(11 * Limits.ASR_RATE), 11 * Limits.ASR_RATE)
                audio.read(0, 11 * Limits.ASR_RATE)
                fail("Readers must not allocate recording-sized slices")
            }
        } catch (_: IllegalArgumentException) { }
        assertFalse(file.exists())
    }
}
