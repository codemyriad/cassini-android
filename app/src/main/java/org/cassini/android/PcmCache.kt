package org.cassini.android

import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Decode-once cache of a recording as raw little-endian float32 16 kHz mono in `<key>.f32`, with a
 * `<key>.json` sidecar recording how far a decode got. The file lives in cacheDir: it can always be
 * rebuilt, so eviction only costs a fresh decode. Keys are the Ogg Opus audio digest (the format's
 * own, so the .opus file is never touched) or the SHA-256 of other input bytes.
 *
 * A writer appends and checkpoints; readers ([read], [available]) may run on other threads while
 * the decode is still going and block until the samples they need exist.
 */
class PcmCache private constructor(val directory: File, val key: String) {
    data class State(val expectedSamples: Long, val decodedSamples: Long, val sourceInputUs: Long, val complete: Boolean)

    val data = File(directory, "$key.f32")
    val sidecar = File(directory, "$key.json")
    private val lock = ReentrantLock()
    private val grown = lock.newCondition()
    private var channel: FileChannel? = null
    private val buffer = ByteBuffer.allocateDirect(BUFFER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    private var written = 0L
    private var lastCheckpoint = 0L
    @Volatile var state: State = recover(); private set

    /** Samples readable now, flushed or not. */
    val available: Long get() = lock.withLock { written.coerceAtLeast(state.decodedSamples) }

    /**
     * The state a crash left behind. A sidecar is written after its samples are forced, but a torn
     * sidecar or file is still possible: trust the smaller of the two counts and cut the file there.
     */
    private fun recover(): State {
        val saved = try {
            val json = JSONObject(sidecar.readText())
            require(json.getInt("version") == VERSION && json.getInt("sampleRate") == Limits.ASR_RATE)
            State(json.getLong("expectedSamples"), json.getLong("decodedSamples"), json.optLong("sourceInputUs"), json.getBoolean("complete"))
        } catch (_: Exception) { State(0, 0, 0, false) }
        val onDisk = if (data.exists()) data.length() / 4 else 0
        val decoded = minOf(saved.decodedSamples, onDisk)
        if (data.exists() && data.length() != decoded * 4) RandomAccessFile(data, "rw").use { it.setLength(decoded * 4) }
        return saved.copy(decodedSamples = decoded, complete = saved.complete && decoded == saved.decodedSamples,
            sourceInputUs = if (decoded == saved.decodedSamples) saved.sourceInputUs else 0)
    }

    /** Starts or continues writing at [State.decodedSamples]. */
    fun openWriter(expectedSamples: Long) = lock.withLock {
        check(channel == null)
        directory.mkdirs()
        val file = RandomAccessFile(data, "rw")
        file.setLength(state.decodedSamples * 4)
        channel = file.channel.also { it.position(state.decodedSamples * 4) }
        written = state.decodedSamples
        lastCheckpoint = written
        state = state.copy(expectedSamples = expectedSamples)
        writeSidecar()
    }

    /** The sink a decoder pushes into. [sourceInputUs] is the input position the next checkpoint records. */
    fun append(samples: FloatArray, count: Int, sourceInputUs: Long = 0) {
        lock.withLock {
            val out = checkNotNull(channel)
            for (i in 0 until count) {
                if (!buffer.hasRemaining()) drain(out)
                buffer.putFloat(samples[i])
            }
            written += count
            if (written - lastCheckpoint >= CHECKPOINT_SAMPLES) checkpoint(sourceInputUs)
            grown.signalAll()
        }
    }

    /** Forces samples to disk, then records them in the sidecar. */
    fun checkpoint(sourceInputUs: Long = state.sourceInputUs) = lock.withLock {
        val out = checkNotNull(channel)
        drain(out)
        out.force(false)
        lastCheckpoint = written
        state = state.copy(decodedSamples = written, sourceInputUs = sourceInputUs)
        writeSidecar()
    }

    fun finish() = lock.withLock {
        checkpoint()
        state = state.copy(complete = true, expectedSamples = written)
        writeSidecar()
        closeWriter()
        grown.signalAll()
    }

    /** Ends a decode that stopped early: what was checkpointed stays, and readers wake to see the end. */
    fun abandon() = lock.withLock {
        try { if (channel != null) checkpoint() } catch (_: Exception) {}
        closeWriter()
        abandoned = true
        grown.signalAll()
    }
    private var abandoned = false

    private fun closeWriter() { channel?.close(); channel = null }

    private fun drain(out: FileChannel) {
        buffer.flip()
        while (buffer.hasRemaining()) out.write(buffer)
        buffer.clear()
    }

    private fun writeSidecar() {
        val json = JSONObject().put("version", VERSION).put("sampleRate", Limits.ASR_RATE)
            .put("expectedSamples", state.expectedSamples).put("decodedSamples", state.decodedSamples)
            .put("sourceInputUs", state.sourceInputUs).put("complete", state.complete)
        val temp = File(directory, "$key.json.tmp")
        temp.outputStream().use { it.write(json.toString().toByteArray(Charsets.UTF_8)); it.fd.sync() }
        check(temp.renameTo(sidecar))
    }

    /**
     * Blocks until [end] samples exist or the input has ended, and returns how many are readable
     * (possibly fewer than [end] at the end). Throws [InterruptedException] when interrupted.
     */
    fun awaitAvailable(end: Long): Long = lock.withLock {
        while (written.coerceAtLeast(state.decodedSamples) < end && !state.complete && !abandoned) grown.await()
        written.coerceAtLeast(state.decodedSamples)
    }

    /** Copies samples [start, start + length) into [into]; the samples must already be available. */
    fun read(start: Long, length: Int, into: FloatArray, offset: Int = 0) {
        lock.withLock {
            require(start >= 0 && start + length <= written.coerceAtLeast(state.decodedSamples))
            channel?.let { drain(it) }
        }
        val bytes = ByteBuffer.allocate(length * 4).order(ByteOrder.LITTLE_ENDIAN)
        RandomAccessFile(data, "r").use { file ->
            val channel = file.channel
            var position = start * 4
            while (bytes.hasRemaining()) {
                val n = channel.read(bytes, position)
                if (n < 0) throw java.io.EOFException()
                position += n
            }
        }
        bytes.flip()
        bytes.asFloatBuffer().get(into, offset, length)
    }

    /** The whole cache as one array, for consumers that still need it (diarization). */
    fun readAll(): FloatArray {
        val total = awaitAvailable(Long.MAX_VALUE)
        require(total <= Int.MAX_VALUE)
        return FloatArray(total.toInt()).also { read(0, it.size, it) }
    }

    fun delete() = lock.withLock { closeWriter(); data.delete(); sidecar.delete() }

    companion object {
        const val VERSION = 1
        private const val BUFFER_BYTES = 256 * 1024
        const val CHECKPOINT_SAMPLES = 10L * Limits.ASR_RATE

        fun open(directory: File, key: String): PcmCache {
            require(key.matches(Regex("[0-9a-zA-Z_-]{8,128}")))
            return PcmCache(directory, key)
        }

        /** Keeps the [keep] most recently used caches (the [except] key always survives). */
        fun trim(directory: File, keep: Int, except: String? = null) {
            val caches = directory.listFiles { f -> f.name.endsWith(".f32") }.orEmpty()
                .filter { it.nameWithoutExtension != except }.sortedByDescending { it.lastModified() }
            caches.drop(keep).forEach { f ->
                f.delete(); File(directory, f.nameWithoutExtension + ".json").delete()
            }
        }

        fun sha256(file: File): String {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val block = ByteArray(64 * 1024)
                while (true) { val n = input.read(block); if (n < 0) break; hash.update(block, 0, n) }
            }
            return hash.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
