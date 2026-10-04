package org.cassini.android

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Mono PCM16 RIFF WAV at [sampleRate]. The size fields are patched by [close]. */
internal class PcmWaveWriter(val file: File, private val sampleRate: Int = 16000) : Closeable {
    private val out = RandomAccessFile(file, "rw")
    private val bytes = ByteBuffer.allocate(BLOCK_SAMPLES * 2).order(ByteOrder.LITTLE_ENDIAN)
    private var dataBytes = 0L
    private var closed = false
    val samplesWritten: Long get() = dataBytes / 2

    init {
        try {
            out.setLength(0)
            out.write(header(0))
        } catch (error: IOException) { out.close(); throw error }
        catch (error: RuntimeException) { out.close(); throw error }
    }

    @Synchronized fun write(samples: ShortArray, count: Int = samples.size) {
        check(!closed) { "Writer is closed" }
        require(count in 0..samples.size) { "count $count outside 0..${samples.size}" }
        require(dataBytes + count * 2L <= MAX_DATA_BYTES) { "WAV data would exceed 4 GiB" }
        var at = 0
        while (at < count) {
            val n = minOf(BLOCK_SAMPLES, count - at)
            bytes.clear()
            for (i in at until at + n) bytes.putShort(samples[i])
            out.write(bytes.array(), 0, n * 2)
            dataBytes += n * 2L
            at += n
        }
    }

    /** Patches the sizes and syncs to disk. Safe to call again. */
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        try {
            out.seek(0)
            out.write(header(dataBytes))
            out.fd.sync()
        } finally { out.close() }
    }

    private fun header(data: Long): ByteArray = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII)); putInt((36 + data).toInt())
        put("WAVE".toByteArray(Charsets.US_ASCII))
        put("fmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
        putShort(1); putShort(1); putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
        put("data".toByteArray(Charsets.US_ASCII)); putInt(data.toInt())
    }.array()

    companion object {
        const val HEADER_BYTES = 44
        private const val BLOCK_SAMPLES = 4096
        private const val MAX_DATA_BYTES = 0xFFFF_FFFFL - 36
    }
}
