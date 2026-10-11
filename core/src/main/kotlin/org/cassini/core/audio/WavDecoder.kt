package org.cassini.core.audio

import org.cassini.core.Failure
import org.cassini.core.requireUser
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The pure-JVM half of 0.1.0 `AudioDecoder`: the WAV reader and the codec frame clock. */
object WavDecoder {
    /**
     * Codec timestamps are whole microseconds, so a buffer that continues the previous one can
     * name a time just short of it: 48 kHz AAC frames last 21333.3 µs. Only a gap or overlap
     * beyond a millisecond moves the write position away from [next]. The first buffer has no
     * predecessor and keeps its own time.
     */
    fun framePosition(presentationTimeUs: Long, sampleRate: Int, next: Long?): Long {
        val nominal = (presentationTimeUs.coerceAtLeast(0) * sampleRate + 500_000) / 1_000_000
        return if (next != null && kotlin.math.abs(nominal - next) <= sampleRate / 1000) next else nominal
    }

    fun decodeWav(file: RandomAccessFile, onSamples: (FloatArray, Int) -> Unit = { _, _ -> }, retainPcm: Boolean = true, startSample: Long = 0, onProgress: (Long, Long?) -> Unit = { _, _ -> }): PcmAudio {
        var sampleRate = 0
        var channels = 0
        var dataOffset = -1L
        var dataSize = 0L
        file.seek(12)
        while (file.filePointer + 8 <= file.length()) {
            val id = ByteArray(4).also(file::readFully).toString(Charsets.US_ASCII)
            val size = Integer.reverseBytes(file.readInt()).toLong() and 0xffffffffL
            val offset = file.filePointer
            requireUser(offset + size <= file.length(), Failure.AUDIO)
            when (id) {
                "fmt " -> {
                    requireUser(size >= 16, Failure.AUDIO)
                    val format = java.lang.Short.reverseBytes(file.readShort()).toInt() and 0xffff
                    channels = java.lang.Short.reverseBytes(file.readShort()).toInt() and 0xffff
                    sampleRate = Integer.reverseBytes(file.readInt())
                    file.skipBytes(6)
                    val bits = java.lang.Short.reverseBytes(file.readShort()).toInt() and 0xffff
                    requireUser(format == 1 && bits == 16, Failure.WAV)
                }
                "data" -> { dataOffset = offset; dataSize = size }
            }
            file.seek(offset + size + (size and 1))
        }
        requireUser(sampleRate in 8000..96000 && channels in 1..2 && dataOffset >= 0, Failure.AUDIO)
        requireUser(dataSize % (2 * channels) == 0L, Failure.AUDIO)
        val frames = dataSize / (2 * channels)
        requireUser(frames >= 1, Failure.LONG)
        Limits.requireDuration(frames * 1000 / sampleRate, retainPcm)
        val out = if (retainPcm) PcmBuilder(((frames * Limits.ASR_RATE + sampleRate / 2) / sampleRate).toInt()) else null
        val initialFrame = (startSample * sampleRate / Limits.ASR_RATE - sampleRate).coerceAtLeast(0)
        require(initialFrame < frames)
        val resampler = StreamingResampler(sampleRate, initialFrame, startSample) { samples, size ->
            out?.append(samples, size)
            onSamples(samples, size)
        }
        file.seek(dataOffset + initialFrame * 2 * channels)
        val block = ByteArray(64 * 1024)
        val mono = FloatArray(block.size / 2)
        val buffer = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN)
        var remaining = dataSize - initialFrame * 2 * channels
        val durationMs = frames * 1000 / sampleRate
        onProgress(startSample * 1000 / Limits.ASR_RATE, durationMs)
        while (remaining > 0) {
            requireUser(!Thread.currentThread().isInterrupted, Failure.CANCELLED)
            val n = minOf(block.size.toLong(), remaining).toInt()
            file.readFully(block, 0, n)
            remaining -= n
            buffer.clear()
            val count = n / (2 * channels)
            for (i in 0 until count) {
                var sum = 0f
                repeat(channels) { sum += buffer.short / 32768f }
                mono[i] = sum / channels
            }
            resampler.push(mono, 0, count)
            onProgress((dataSize - remaining) / (2 * channels) * 1000 / sampleRate, durationMs)
        }
        resampler.finish()
        return PcmAudio(out?.build() ?: FloatArray(0), Limits.ASR_RATE)
    }
}
