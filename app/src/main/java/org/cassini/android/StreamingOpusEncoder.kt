package org.cassini.android

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import org.json.JSONObject

/** Position-independent libopus state allows an exact continuation after unloading the encoder. */
internal object NativeOpus {
    init { System.loadLibrary("cassini_opus") }
    external fun create(): Long
    external fun lookahead(ptr: Long): Int
    external fun encode(ptr: Long, frame: FloatArray): ByteArray
    external fun save(ptr: Long): ByteArray
    external fun restore(ptr: Long, state: ByteArray)
    external fun destroy(ptr: Long)
}

/** Same-pass 16 kHz mono encoding; checkpoints retain the codec and Ogg clocks without re-encoding. */
internal class StreamingOpusEncoder(private val file: File, restored: JSONObject? = null) : AutoCloseable {
    private val ptr = NativeOpus.create()
    private val output: FileOutputStream
    private val stream: java.io.BufferedOutputStream
    private val frame = FloatArray(320)
    private var pending = 0
    var sampleCount = 0L
        private set
    private val head: ByteArray
    private val muxer: OggOpus.LiveMuxer
    private var closed = false
    private var finished = false
    init {
        var opened: FileOutputStream? = null
        try {
            if (restored != null) {
                require(restored.getString("runtime") == "opus-1.5.2-${android.os.Build.SUPPORTED_ABIS[0]}")
                val codecState = CheckpointData.bytes(restored.getString("codec"))
                require(OggOpus.hex(java.security.MessageDigest.getInstance("SHA-256").digest(codecState)) == restored.getString("codecHash"))
                NativeOpus.restore(ptr, codecState)
                sampleCount = restored.getLong("samples")
                pending = restored.getInt("pending")
                require(sampleCount in 0..Limits.MAX_SAMPLES && pending in 0..319)
                CheckpointData.floats(restored.getString("frame"), 320).also { require(it.size == 320) }.copyInto(frame)
                val length = restored.getLong("length")
                require(length >= 0 && file.length() >= length)
                RandomAccessFile(file, "rw").use { it.setLength(length) }
            }
            head = ByteArray(19).apply {
                "OpusHead".toByteArray().copyInto(this); this[8] = 1; this[9] = 1
                val skip = NativeOpus.lookahead(ptr) * 3
                this[10] = skip.toByte(); this[11] = (skip shr 8).toByte()
                OggOpus.put32(this, 12, 16000)
            }
            output = FileOutputStream(file, restored != null)
            opened = output
            stream = output.buffered()
            val state = restored?.let { OggOpus.LiveState(it.getInt("serial"), it.getInt("sequence"),
                it.optString("packet").takeIf(String::isNotEmpty)?.let(CheckpointData::bytes), it.getLong("granule")) }
            muxer = OggOpus.LiveMuxer(stream, head, CassiniDocument.tagsPacket(emptyList()), state?.serial ?: kotlin.random.Random.nextInt(), state)
        } catch (error: Throwable) { try { opened?.close() } finally { NativeOpus.destroy(ptr) }; throw error }
    }
    fun accept(samples: FloatArray, count: Int) {
        check(!closed && !finished); require(count in 0..samples.size)
        requireUser(sampleCount + count <= Limits.MAX_SAMPLES, Failure.LONG)
        sampleCount += count
        var offset = 0
        while (offset < count) {
            val n = minOf(320 - pending, count - offset)
            samples.copyInto(frame, pending, offset, offset + n)
            offset += n; pending += n
            if (pending == 320) { muxer.add(NativeOpus.encode(ptr, frame)); pending = 0 }
        }
    }
    fun checkpoint(): JSONObject {
        check(!closed && !finished)
        stream.flush(); output.fd.sync()
        val state = muxer.checkpoint()
        val codecState = NativeOpus.save(ptr)
        return JSONObject().put("runtime", "opus-1.5.2-${android.os.Build.SUPPORTED_ABIS[0]}")
            .put("codec", CheckpointData.bytes(codecState)).put("codecHash", OggOpus.hex(java.security.MessageDigest.getInstance("SHA-256").digest(codecState))).put("frame", CheckpointData.floats(frame))
            .put("samples", sampleCount).put("pending", pending).put("length", file.length())
            .put("serial", state.serial).put("sequence", state.sequence).put("granule", state.granule)
            .put("packet", state.pending?.let(CheckpointData::bytes) ?: "")
    }
    fun finish() {
        check(!closed && !finished); requireUser(sampleCount > 0, Failure.EMPTY)
        val target = sampleCount * 3 + muxer.preSkip
        if (pending > 0) { frame.fill(0f, pending); muxer.add(NativeOpus.encode(ptr, frame)); pending = 0 }
        frame.fill(0f)
        while (muxer.granule < target) muxer.add(NativeOpus.encode(ptr, frame))
        muxer.finish(target); stream.flush(); output.fd.sync(); finished = true
    }
    override fun close() {
        if (closed) return
        closed = true
        try { stream.close() } finally { NativeOpus.destroy(ptr) }
    }
}
