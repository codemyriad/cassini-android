package io.codemyriad.cassini.core.recorder

import java.io.IOException
import java.io.OutputStream
import java.time.LocalDateTime

/** The waits of an [XrecClient], in milliseconds. */
data class XrecTimeouts(
    /** The wait for the reply line of a command, and for each PUT acknowledgement. */
    val replyMillis: Long = 5_000,
    /** The wait for the next GETS frame. */
    val frameMillis: Long = 5_000,
    /** A resync ends after this much silence. */
    val quietMillis: Long = 300,
    /** A resync gives up waiting for silence after this long. */
    val drainMillis: Long = 5_000,
)

/**
 * A blocking client for the recorder's XREC protocol (open_fw, Bluetooth SPP). One link carries text lines and the
 * binary frames of GETS; [input] gives its received bytes with timeouts, [output] takes the commands. The client is not
 * thread-safe: one thread runs one operation at a time, and closing the link from another thread cancels it.
 *
 * Downloads verify each frame's offset and CRC and retry from the last good offset; uploads send one PUT chunk at a
 * time and resend a chunk whose acknowledgement did not arrive (the device acknowledges an identical resend without
 * writing it twice). [maxRetries] bounds the consecutive failed attempts of one transfer.
 */
@Suppress("TooManyFunctions", "ThrowsCount") // One protocol session; each failure throws its own typed error.
class XrecClient(
    private val input: XrecInput,
    private val output: OutputStream,
    private val timeouts: XrecTimeouts = XrecTimeouts(),
    private val clock: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
    private val maxRetries: Int = DEFAULT_RETRIES,
) {
    private val buffer = ByteArray(BUFFER_BYTES)
    private val scratch = ByteArray(BUFFER_BYTES)
    private var start = 0
    private var end = 0

    /** `PING`: works without authentication. */
    fun ping(): XrecReply.Pong = request(XrecCommands.ping())

    /** `PAIR`: binds [key] with the [pin] that the recorder shows in its pairing window, and authenticates. */
    fun pair(pin: String, key: ByteArray) = requestOk(XrecCommands.pair(pin, key), "PAIR")

    /** `CHAL` and `AUTH` with the bound [key]. */
    fun authenticate(key: ByteArray) {
        val challenge = request<XrecReply.Challenge>(XrecCommands.challenge())
        requestOk(XrecCommands.auth(XrecAuth.response(key, challenge.nonceHex)), "AUTH")
    }

    fun stat(): XrecReply.Status = request(XrecCommands.stat())

    fun rtc(): XrecReply.Clock = request(XrecCommands.rtc())

    fun setRtc(time: LocalDateTime) = requestOk(XrecCommands.setRtc(time), "RTC")

    /** `LS`: the files of /record, in directory order. */
    fun list(): List<XrecReply.Entry> {
        send(XrecCommands.list())
        val entries = mutableListOf<XrecReply.Entry>()
        while (true) {
            when (val reply = readReply(deadline(timeouts.replyMillis))) {
                is XrecReply.Entry -> entries += reply
                XrecReply.End -> return entries
                else -> throw unexpected(reply)
            }
        }
    }

    fun delete(name: String) = requestOk(XrecCommands.delete(name), "DEL")

    /**
     * Downloads /record/[name] of the listed [size] into [sink], starting at [resumeOffset] (the bytes [sink] already
     * has). Returns the final offset, equal to [size]. [progress] gets the offset after each good frame.
     *
     * The end comes from [size], not from a short frame: the device shortens frames to its link MTU.
     */
    fun download(
        name: String,
        size: Long,
        sink: OutputStream,
        resumeOffset: Long = 0,
        progress: (Long) -> Unit = {},
    ): Long {
        require(size in 0..XrecLimits.MAX_OFFSET + 1 && resumeOffset in 0..size) { "Bad size or resume offset" }
        val transfer = Transfer(resumeOffset)
        var failures = 0
        while (transfer.offset < size) {
            val before = transfer.offset
            try {
                burst(name, size, transfer, sink, progress)
            } catch (e: XrecException) {
                if (!e.transient) throw e
                failures = if (transfer.offset > before) 1 else failures + 1
                // Frames of the burst can still arrive: the link is quiet again before a retry or the next command.
                drain()
                if (failures > maxRetries) {
                    val message = "Download of $name failed at ${transfer.offset}"
                    throw XrecException(XrecFailure.INTEGRITY, message, cause = e)
                }
            }
        }
        return transfer.offset
    }

    /**
     * Uploads [bytes] as the transcript sidecar /record/[name] (target `REC`) and commits it with PUTEND, which
     * replaces an older file atomically. Fails with [XrecFailure.FIRMWARE_TOO_OLD] when the device does not know the
     * `REC` target, and with [XrecFailure.NOT_FOUND] when the recording of the sidecar is not on the device (at the
     * first chunk, or at PUTEND when it was deleted meanwhile). [progress] gets the acknowledged byte count after each
     * chunk.
     *
     * The first chunk is short ([XrecLimits.PROBE_CHUNK]): firmware without the `REC` target feeds the payload of the
     * refused header to its line parser, so the probe gives it as few bytes as possible.
     */
    fun uploadSidecar(name: String, bytes: ByteArray, progress: (Int) -> Unit = {}) {
        val target = PutTarget.Record(name)
        require(bytes.isNotEmpty()) { "Nothing to upload" }
        var offset = 0
        var failures = 0
        var restarts = 0
        while (offset < bytes.size) {
            val chunk = if (offset == 0) XrecLimits.PROBE_CHUNK else XrecLimits.PUT_CHUNK
            val length = minOf(chunk, bytes.size - offset)
            when (putChunk(target, bytes, offset, length)) {
                PutResult.ACCEPTED -> {
                    offset += length
                    failures = 0
                    progress(offset)
                }
                PutResult.RETRY -> {
                    if (++failures > maxRetries) {
                        throw XrecException(XrecFailure.INTEGRITY, "Upload of $name failed at $offset")
                    }
                    resync()
                }
                PutResult.RESTART -> {
                    if (++restarts > maxRetries) {
                        throw XrecException(XrecFailure.INTEGRITY, "Upload of $name lost its place")
                    }
                    offset = 0
                }
            }
        }
        commit(name, bytes.size)
    }

    /**
     * Brings the link back to a known reply: a CR ends any partial line, then `PING`, and every reply up to its `PONG`
     * is discarded. Used after a lost reply or a refused PUT header, whose payload older firmware reads as lines (each
     * with its own `ERR` reply). Fails with [XrecFailure.TIMEOUT] when no `PONG` comes.
     */
    fun resync() {
        writeBytes(RESYNC)
        val deadline = deadline(timeouts.drainMillis)
        while (readReply(deadline) !is XrecReply.Pong) {
            // A reply that came before the PONG belongs to an earlier command.
        }
    }

    /**
     * `PUTEND`, sent again after a lost reply: the device answers a repeated PUTEND with the same `OK,PUTEND,<total>`.
     * Older firmware answers `ERR,NOPUT` to the repeat; then LS confirms the commit by the name and the size.
     */
    private fun commit(name: String, total: Int) {
        var attempts = 0
        while (true) {
            send(XrecCommands.putEnd())
            val reply = try {
                replyAfterLateAcks(Long.MAX_VALUE)
            } catch (e: XrecException) {
                if (e.failure != XrecFailure.TIMEOUT || ++attempts > maxRetries) throw e
                resync()
                continue
            }
            when {
                reply is XrecReply.Ok && reply.tag == "PUTEND" && reply.fields.firstOrNull() == total.toString() -> return
                reply is XrecReply.Failed && reply.failure == XrecFailure.NO_UPLOAD && attempts > 0 -> {
                    confirmCommitted(name, total)
                    return
                }
                else -> throw unexpected(reply)
            }
        }
    }

    private fun confirmCommitted(name: String, total: Int) {
        val committed = list().any { it.name.equals(name, ignoreCase = true) && it.size == total.toLong() }
        if (!committed) throw XrecException(XrecFailure.INTEGRITY, "$name is not on the recorder after PUTEND")
    }

    private fun burst(name: String, size: Long, transfer: Transfer, sink: OutputStream, progress: (Long) -> Unit) {
        val total = minOf(XrecLimits.GETS_TOTAL.toLong(), size - transfer.offset).toInt()
        val stop = transfer.offset + total
        send(XrecCommands.gets(transfer.offset, total, XrecLimits.GETS_CHUNK, name))
        while (transfer.offset < stop) {
            val frame = readFrame(deadline(timeouts.frameMillis))
            when {
                frame.offset != transfer.offset ->
                    throw XrecException(XrecFailure.INTEGRITY, "Frame at ${frame.offset}, expected ${transfer.offset}")
                frame.payload.isEmpty() ->
                    throw XrecException(XrecFailure.SIZE_CHANGED, "$name ended at ${transfer.offset}, not at $size")
                frame.payload.size > stop - transfer.offset ->
                    throw XrecException(XrecFailure.INTEGRITY, "Frame longer than requested")
                Crc16CcittFalse.compute(frame.payload) != frame.crc ->
                    throw XrecException(XrecFailure.INTEGRITY, "CRC error in the frame at ${frame.offset}")
            }
            sink.write(frame.payload) // A local storage failure is a plain IOException, not an XrecException.
            transfer.offset += frame.payload.size
            progress(transfer.offset)
        }
    }

    private fun putChunk(target: PutTarget, bytes: ByteArray, offset: Int, length: Int): PutResult {
        val crc = Crc16CcittFalse.compute(bytes, offset, length)
        val header = XrecCommands.putHeader(offset.toLong(), length, crc, target).toByteArray(Charsets.UTF_8)
        writeBytes(header + bytes.copyOfRange(offset, offset + length))
        val reply = try {
            replyAfterLateAcks(offset.toLong())
        } catch (e: XrecException) {
            if (e.transient) return PutResult.RETRY
            throw e
        }
        return when (reply) {
            is XrecReply.Ok -> {
                val acknowledged = reply.tag == "PUT" && reply.fields == listOf(offset.toString(), length.toString())
                if (acknowledged) PutResult.ACCEPTED else PutResult.RETRY
            }
            is XrecReply.Failed -> rejected(reply, offset)
            else -> PutResult.RETRY
        }
    }

    private fun rejected(reply: XrecReply.Failed, offset: Int): PutResult = when (reply.failure) {
        XrecFailure.CRC -> PutResult.RETRY
        XrecFailure.OFFSET -> if (offset > 0) PutResult.RESTART else throw unexpected(reply)
        XrecFailure.BAD_ARGUMENT, XrecFailure.UNSUPPORTED, XrecFailure.BAD_NAME -> {
            // Older firmware reads the payload of a refused header as command lines: resync before going on.
            resync()
            if (offset == 0 && reply.failure != XrecFailure.BAD_NAME) {
                val message = "The recorder firmware is too old for transcript upload"
                throw XrecException(XrecFailure.FIRMWARE_TOO_OLD, message, reply.reason)
            }
            throw unexpected(reply)
        }
        else -> throw unexpected(reply)
    }

    /**
     * The next reply that is not a late `OK,PUT` for a chunk before [before]: the first reply of a chunk that was sent
     * again after a timeout can arrive after the resend.
     */
    private fun replyAfterLateAcks(before: Long): XrecReply {
        val deadline = deadline(timeouts.replyMillis)
        while (true) {
            val reply = readReply(deadline)
            val late = reply is XrecReply.Ok && reply.tag == "PUT" &&
                (reply.fields.firstOrNull()?.toLongOrNull() ?: before) < before
            if (!late) return reply
        }
    }

    private inline fun <reified T : XrecReply> request(command: String): T {
        send(command)
        val reply = readReply(deadline(timeouts.replyMillis))
        return reply as? T ?: throw unexpected(reply)
    }

    private fun requestOk(command: String, tag: String) {
        val reply = request<XrecReply.Ok>(command)
        if (reply.tag != tag) throw unexpected(reply)
    }

    private fun unexpected(reply: XrecReply): XrecException = when (reply) {
        is XrecReply.Failed -> XrecException(reply.failure, "The recorder replied ERR,${reply.reason}", reply.reason)
        else -> XrecException(XrecFailure.PROTOCOL, "Unexpected reply $reply")
    }

    private fun send(command: String) = writeBytes((command + "\r").toByteArray(Charsets.UTF_8))

    private fun writeBytes(bytes: ByteArray) {
        try {
            output.write(bytes)
            output.flush()
        } catch (e: IOException) {
            throw XrecException(XrecFailure.DISCONNECTED, "The link closed", cause = e)
        }
    }

    private fun deadline(millis: Long): Long = clock() + millis

    private fun readReply(deadline: Long): XrecReply {
        while (true) {
            XrecReplies.parse(readLine(deadline))?.let { return it }
        }
    }

    private fun readLine(deadline: Long): String {
        while (true) {
            for (i in start until end) {
                if (buffer[i] == LF) {
                    val line = String(buffer, start, i - start, Charsets.UTF_8).trimEnd('\r')
                    start = i + 1
                    return line
                }
            }
            fill(deadline)
        }
    }

    /** The next GETS frame; an `ERR` line instead fails with the device's reason, any other line is a desync. */
    private fun readFrame(deadline: Long): Frame {
        while (true) {
            ensure(1, deadline)
            when (buffer[start]) {
                CR, LF -> start++
                FRAME_MAGIC[0] -> return frame(deadline)
                else -> XrecReplies.parse(readLine(deadline))?.let { throw unexpected(it) }
            }
        }
    }

    private fun frame(deadline: Long): Frame {
        ensure(XrecLimits.FRAME_HEADER, deadline)
        if (!FRAME_MAGIC.indices.all { buffer[start + it] == FRAME_MAGIC[it] }) {
            throw XrecException(XrecFailure.PROTOCOL, "Lost the frame boundary")
        }
        val offset = u32(start + OFFSET_FIELD)
        val length = u16(start + LENGTH_FIELD)
        val crc = u16(start + CRC_FIELD)
        if (length > XrecLimits.MAX_CHUNK) throw XrecException(XrecFailure.PROTOCOL, "Frame of $length bytes")
        ensure(XrecLimits.FRAME_HEADER + length, deadline)
        val from = start + XrecLimits.FRAME_HEADER
        val payload = buffer.copyOfRange(from, from + length)
        start = from + length
        return Frame(offset, payload, crc)
    }

    private fun u16(at: Int): Int = (buffer[at].toInt() and BYTE) or ((buffer[at + 1].toInt() and BYTE) shl BYTE_BITS)

    private fun u32(at: Int): Long = u16(at).toLong() or (u16(at + 2).toLong() shl (2 * BYTE_BITS))

    private fun ensure(bytes: Int, deadline: Long) {
        while (end - start < bytes) fill(deadline)
    }

    private fun fill(deadline: Long) {
        if (start > 0) {
            buffer.copyInto(buffer, 0, start, end)
            end -= start
            start = 0
        }
        if (end == buffer.size) throw XrecException(XrecFailure.PROTOCOL, "Reply longer than ${buffer.size} bytes")
        val wait = deadline - clock()
        if (wait <= 0) throw XrecException(XrecFailure.TIMEOUT, "No reply from the recorder")
        val n = readInput(buffer, end, buffer.size - end, wait)
        if (n == 0) throw XrecException(XrecFailure.TIMEOUT, "No reply from the recorder")
        end += n
    }

    private fun readInput(into: ByteArray, offset: Int, length: Int, wait: Long): Int {
        val n = try {
            input.read(into, offset, length, wait)
        } catch (e: IOException) {
            throw XrecException(XrecFailure.DISCONNECTED, "The link closed", cause = e)
        }
        if (n < 0) throw XrecException(XrecFailure.DISCONNECTED, "The link closed")
        return n
    }

    /** Discards the buffered bytes and what arrives until [XrecTimeouts.quietMillis] of silence. */
    private fun drain() {
        start = 0
        end = 0
        val limit = clock() + timeouts.drainMillis
        while (clock() < limit) {
            if (readInput(scratch, 0, scratch.size, timeouts.quietMillis) == 0) return
        }
    }

    private class Transfer(var offset: Long)

    private class Frame(val offset: Long, val payload: ByteArray, val crc: Int)

    private enum class PutResult { ACCEPTED, RETRY, RESTART }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val DEFAULT_RETRIES = 5
        const val BUFFER_BYTES = 4096
        const val CR = '\r'.code.toByte()
        val RESYNC = "\r${XrecCommands.ping()}\r".toByteArray(Charsets.US_ASCII)
        const val LF = '\n'.code.toByte()
        val FRAME_MAGIC = "XREC".toByteArray(Charsets.US_ASCII)
        const val OFFSET_FIELD = 4
        const val LENGTH_FIELD = 8
        const val CRC_FIELD = 10
        const val BYTE = 0xFF
        const val BYTE_BITS = 8
    }
}
