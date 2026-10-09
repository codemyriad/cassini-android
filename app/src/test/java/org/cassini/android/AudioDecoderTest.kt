package org.cassini.android

import org.junit.Assert.*
import org.junit.Test

class AudioDecoderTest {
    @Test fun truncatedFrameTimesStayContiguous() {
        for ((rate, frame) in listOf(48000 to 1024, 44100 to 1024, 44100 to 1152, 16000 to 1024, 48000 to 960)) {
            var next: Int? = null
            repeat(2000) { index ->
                val start = index.toLong() * frame
                // Extractors and codecs report whole microseconds, truncated.
                assertEquals("$rate Hz frame $index", start.toInt(), AudioDecoder.framePosition(start * 1_000_000 / rate, rate, next))
                next = start.toInt() + frame
            }
        }
    }

    @Test fun realContainerGapsAndOverlapsKeepTheirClock() {
        assertEquals(96000, AudioDecoder.framePosition(2_000_000, 48000, 48000))
        assertEquals(24000, AudioDecoder.framePosition(500_000, 48000, 48000))
        assertEquals(0, AudioDecoder.framePosition(-20_000, 48000, null))
        // A first buffer has nothing to continue, so even a sub-millisecond start offset is kept.
        assertEquals(43, AudioDecoder.framePosition(900, 48000, null))
        assertEquals(0, AudioDecoder.framePosition(900, 48000, 0))
        // Timestamp jitter below a millisecond is not a gap.
        assertEquals(48000, AudioDecoder.framePosition(1_000_900, 48000, 48000))
        assertEquals(48096, AudioDecoder.framePosition(1_002_000, 48000, 48000))
    }

    private fun wav(rate: Int, channels: Int, pcm: ShortArray, extraChunk: Boolean = true): java.io.File {
        val data = java.nio.ByteBuffer.allocate(pcm.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN).also { b -> pcm.forEach { b.putShort(it) } }.array()
        val out = java.io.ByteArrayOutputStream()
        fun u32(v: Int) = out.write(java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun u16(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
        out.write("RIFF".toByteArray()); u32(0); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); u32(16); u16(1); u16(channels); u32(rate); u32(rate * channels * 2); u16(channels * 2); u16(16)
        if (extraChunk) { out.write("LIST".toByteArray()); u32(3); out.write(byteArrayOf(1, 2, 3, 0)) }
        out.write("data".toByteArray()); u32(data.size); out.write(data)
        return java.io.File.createTempFile("decoder", ".wav").also { it.writeBytes(out.toByteArray()); it.deleteOnExit() }
    }

    @Test fun blockWavReaderMatchesWholeFileConversion() {
        val random = kotlin.random.Random(3)
        for ((rate, channels) in listOf(48000 to 2, 44100 to 1, 16000 to 1, 8000 to 2)) {
            val frames = rate * 3 + 17
            val pcm = ShortArray(frames * channels) { random.nextInt(-32768, 32768).toShort() }
            // The previous whole-file reader: average channels at the source rate, then convert.
            val mono = FloatArray(frames) { f -> var sum = 0f; repeat(channels) { sum += pcm[f * channels + it] / 32768f }; sum / channels }
            val expected = resampleTo16k(mono, rate)
            val file = wav(rate, channels, pcm)
            val audio = java.io.RandomAccessFile(file, "r").use { it.seek(OggOpus.HEAD_BYTES.toLong()); AudioDecoder.decodeWav(it) }
            assertEquals(16000, audio.sampleRate)
            assertArrayEquals("$rate Hz x$channels", expected, audio.samples, 1e-5f)
        }
    }

    @Test fun wavProgressTracksDecodedFramesWithoutChangingAudio() {
        val file = wav(48000, 2, ShortArray(48000 * 2 * 3))
        val updates = mutableListOf<Pair<Long, Long?>>()
        val emitted = PcmBuilder(48000)
        try {
            val audio = java.io.RandomAccessFile(file, "r").use {
                AudioDecoder.decodeWav(it, onSamples = emitted::append) { decoded, total -> updates.add(decoded to total) }
            }
            assertArrayEquals(audio.samples, emitted.build(), 0f)
            assertEquals(0L to 3000L, updates.first())
            assertEquals(audio.durationMs to 3000L, updates.last())
            assertTrue(updates.size > 2)
            assertTrue(updates.zipWithNext().all { (a, b) -> a.first <= b.first })
            assertTrue(updates.all { it.second == 3000L })
        } finally { file.delete() }
    }

    @Test fun growableBufferKeepsEverySampleAndTrimsOnlyWrongGuesses() {
        val builder = PcmBuilder(16000)
        val chunk = FloatArray(1000) { it.toFloat() }
        repeat(50) { builder.append(chunk, 1000) }
        assertEquals(50_000, builder.size)
        val samples = builder.build()
        assertEquals(50_000, samples.size)
        assertEquals(999f, samples[49_999])
        val exact = PcmBuilder(16000).apply { repeat(16) { append(chunk, 1000) } }
        assertSame(exact.build(), exact.build())
    }

    @Test fun durationCapAndMemoryGuard() {
        assertEquals(86_400_000L, Limits.MAX_RECORDING_MS)
        // Sample positions are Ints: the ceiling must leave them room.
        assertTrue(Limits.MAX_SAMPLES in 1 until Int.MAX_VALUE && Limits.MAX_SAMPLES.toLong() >= Limits.MAX_RECORDING_MS * Limits.ASR_RATE / 1000)
        assertTrue(Limits.memoryAllows(3_600_000, 400L shl 20))
        assertFalse(Limits.memoryAllows(3_600_000, 200L shl 20))
        try { Limits.requireDuration(Limits.MAX_RECORDING_MS + 1); fail() } catch (e: UserFacingException) { assertEquals(Failure.LONG, e.failure) }
    }
}
