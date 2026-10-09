package org.cassini.android

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Random-access audio for encoding and voiceprints without retaining a recording in RAM. */
internal interface PcmSource {
    val sampleRate: Int
    val sampleCount: Int
    val durationMs: Long get() = sampleCount * 1000L / sampleRate
    fun read(start: Int, count: Int): FloatArray
}

internal fun PcmAudio.source(): PcmSource = object : PcmSource {
    override val sampleRate = this@source.sampleRate
    override val sampleCount = samples.size
    override fun read(start: Int, count: Int) = samples.copyOfRange(start, start + count)
}

/** Temporary float PCM; fixed IO buffers, no array sized to the recording. Owned by one job. */
internal class PcmFile(private val file: File, private val deleteOnClose: Boolean = true) : PcmSource, AutoCloseable {
    private val data = RandomAccessFile(file, "rw").also {
        if (it.length() % 4 != 0L || it.length() / 4 > Limits.MAX_SAMPLES) { it.close(); throw IllegalArgumentException("Invalid PCM cache length") }
    }
    private val bytes = ByteArray(64 * 1024)
    private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    override val sampleRate = Limits.ASR_RATE
    override var sampleCount = (file.length() / 4).toInt()
        private set

    fun append(samples: FloatArray, count: Int) {
        require(count in 0..samples.size)
        requireUser(sampleCount.toLong() + count <= Limits.MAX_SAMPLES, Failure.LONG)
        if (sampleCount / (4 * 1024 * 1024) != (sampleCount + count) / (4 * 1024 * 1024))
            requireUser(file.parentFile!!.usableSpace >= Limits.MIN_FREE_BYTES, Failure.SPACE)
        data.seek(sampleCount * 4L)
        var at = 0
        while (at < count) {
            val n = minOf(bytes.size / 4, count - at)
            buffer.clear()
            repeat(n) { buffer.putFloat(samples[at + it]) }
            data.write(bytes, 0, n * 4)
            at += n
        }
        sampleCount += count
    }

    override fun read(start: Int, count: Int): FloatArray {
        require(start >= 0 && count >= 0 && start.toLong() + count <= sampleCount)
        // Every consumer is bounded to a voiceprint window or a codec buffer.
        require(count <= 10 * sampleRate)
        val result = FloatArray(count)
        data.seek(start * 4L)
        var at = 0
        while (at < count) {
            val n = minOf(bytes.size / 4, count - at)
            data.readFully(bytes, 0, n * 4)
            buffer.clear()
            repeat(n) { result[at + it] = buffer.float }
            at += n
        }
        return result
    }

    fun truncate(samples: Int) { require(samples >= 0 && samples <= sampleCount); data.setLength(samples * 4L); sampleCount = samples }
    fun sync() = data.fd.sync()
    override fun close() { try { data.close() } finally { if (deleteOnClose) file.delete() } }
}
