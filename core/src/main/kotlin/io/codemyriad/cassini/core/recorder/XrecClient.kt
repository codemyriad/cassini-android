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
    /**
     * A resync gives up waiting for silence after this long. The drain after a failed GETS window waits longer, by the
     * time the rest of the window takes at a slow link ([XrecClient.DRAIN_BYTES_PER_SECOND]).
     */
    val drainMillis: Long = 5_000,
)

/**
 * The measurements of one [XrecClient.download]: [bytes] moved in [millis], with [retries] failed windows, [requests]
 * GETS commands and the window of the last request ([window], smaller after a fallback).
 */
data class XrecDownloadStats(
    val bytes: Long,
    val millis: Long,
    val retries: Int,
    val requests: Int,
    val window: Int,
) {
    /** The speed in bytes per second; 0 when no time was measured. */
    val bytesPerSecond: Long get() = if (millis > 0) bytes * MILLIS_PER_SECOND / millis else 0

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}

/**
 * A blocking client for the recorder's XREC protocol (open_fw, Bluetooth SPP). One link carries text lines and the
 * binary frames of GETS; [input] gives its received bytes with timeouts, [output] takes the commands. The client is not
 * thread-safe: one thread runs one operation at a time, and closing the link from another thread cancels it.
 *
 * Downloads ask for [getsWindow] bytes per GETS ([XrecLimits.GETS_WINDOW] on open_fw; [ping] lowers it to
 * [XrecLimits.STOCK_GETS_WINDOW] for the stock firmware), verify each frame's offset and CRC and retry from the last
 * good offset; uploads send one PUT chunk at a time and resend a chunk whose acknowledgement did not arrive (the device
 * acknowledges an identical resend without writing it twice). [maxRetries] bounds the consecutive failed attempts of
 * one transfer.
 */
// One protocol session; each failure throws its own typed error; the constructor takes the seams of the tests.
@Suppress("TooManyFunctions", "ThrowsCount", "LongParameterList")
class XrecClient(
    private val input: XrecInput,
    private val output: OutputStream,
    private val timeouts: XrecTimeouts = XrecTimeouts(),
    private val clock: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
    private val maxRetries: Int = DEFAULT_RETRIES,
    getsWindow: Int = XrecLimits.GETS_WINDOW,
    /** Waits before a GETS window that would start sooner than the pace of a paced download; interruptible. */
    private val sleep: (Long) -> Unit = Thread::sleep,
) {
    private val buffer = ByteArray(BUFFER_BYTES)
    private val scratch = ByteArray(BUFFER_BYTES)
    private var start = 0
    private var end = 0

    /** The window each download starts with: lowered for the rest of the session when the PONG names stock firmware. */
    private var baseWindow: Int = getsWindow

    /**
     * The bytes one GETS asks for. It drops to [XrecLimits.STOCK_GETS_WINDOW] for the rest of the session when the
     * PONG names the stock firmware, and for the rest of one download after [FALLBACK_FAILURES] failed windows in a
     * row; the next download starts again from the session's window.
     */
    var getsWindow: Int = getsWindow
        private set

    /** Hears of each download that falls back to the stock window: the file, the offset and the new window. */
    var onFallback: (name: String, offset: Long, window: Int) -> Unit = { _, _, _ -> }

    /** The measurements of the last download that finished, or null before the first one. */
    var lastDownload: XrecDownloadStats? = null
        private set

    init {
        require(getsWindow > 0) { "Bad GETS window" }
    }

    /** `PING`: works without authentication. The stock firmware keeps the small window of its GETS. */
    fun ping(): XrecReply.Pong = request<XrecReply.Pong>(XrecCommands.ping()).also { pong ->
        if (XrecLimits.STOCK_FIRMWARE_MARK in pong.firmware) {
            baseWindow = minOf(baseWindow, XrecLimits.STOCK_GETS_WINDOW)
            getsWindow = baseWindow
        }
    }

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

    /** `SYNC`: tells the recorder how many downloads/transcriptions/uploads remain (`0` lets background sync shut down). */
    fun reportSync(pending: Int, etaSeconds: Int = 0) = requestOk(XrecCommands.sync(pending, etaSeconds), "SYNC")

    /**
     * Downloads /record/[name] of the listed [size] into [sink], starting at [resumeOffset] (the bytes [sink] already
     * has). Returns the final offset, equal to [size]. [progress] gets the offset after each good frame, and the same
     * offset again as a heartbeat while a recovery skips old frames or drains the link.
     *
     * The end comes from [size], not from a short frame: the device shortens frames to its link MTU. A frame with no
     * payload at the expected offset is the end of the file on the device ([XrecFailure.SIZE_CHANGED]).
     *
     * After a failed window (a timeout, a bad CRC or offset), the device keeps streaming the rest of that window: the
     * client drains it until the link is quiet, then asks again from the last good offset. Frames of the old window
     * that still come after the new GETS are skipped by their offset, within [STALE_BUDGET] bytes. A download that
     * ends while a repeated window can still stream drains the link and resyncs before it returns, so the next command
     * starts on a quiet link. [lastDownload] holds the measurements when the download ends.
     *
     * While the recorder records, [window] lowers the GETS window ([XrecLimits.RECORDING_GETS_WINDOW]) and
     * [pauseMillis] (STAT `pace_ms`) is the shortest interval between the starts of two windows: the device paces the
     * frames itself, so a window that took that long is followed at once, and no pause is added after a window (the
     * device's burst allowance is small: a pause after each window would halve the rate). `ERR,LATER,<ms>` in place of a frame fails with
     * [XrecFailure.LATER]: the bytes up to [XrecException]'s offset are in [sink], and a new download continues there.
     */
    // The file, the sink, the resume point and the pacing of one download; one loop with its recovery.
    @Suppress("LongParameterList", "CyclomaticComplexMethod")
    fun download(
        name: String,
        size: Long,
        sink: OutputStream,
        resumeOffset: Long = 0,
        window: Int? = null,
        pauseMillis: Long = 0,
        progress: (Long) -> Unit = {},
    ): Long {
        require(size in 0..XrecLimits.MAX_OFFSET + 1 && resumeOffset in 0..size) { "Bad size or resume offset" }
        require(window == null || window > 0) { "Bad GETS window" }
        val started = clock()
        val transfer = Transfer(resumeOffset)
        val heartbeat = { progress(transfer.offset) }
        getsWindow = window?.let { minOf(it, baseWindow) } ?: baseWindow
        var failures = 0
        var windowStart = 0L
        while (transfer.offset < size) {
            val before = transfer.offset
            if (pauseMillis > 0 && transfer.requests > 0) {
                // pace_ms is the shortest interval between window starts: a window that took that long goes on at once.
                val rest = pauseMillis - (clock() - windowStart)
                if (rest > 0) {
                    sleep(rest)
                    heartbeat()
                }
            }
            windowStart = clock()
            try {
                burst(name, size, transfer, sink, progress)
            } catch (e: XrecException) {
                if (!e.transient) throw e
                failures = recoverDownload(name, size, transfer, before, failures, heartbeat, e)
            }
        }
        if (transfer.recovering) {
            // Late frames of an old window can complete the file while the repeat of a window still streams.
            drain(drainLimit(transfer.lastWindow), heartbeat)
            stopGets(name, size)
        }
        lastDownload = XrecDownloadStats(
            bytes = transfer.offset - resumeOffset,
            millis = clock() - started,
            retries = transfer.retries,
            requests = transfer.requests,
            window = getsWindow,
        )
        return transfer.offset
    }

    @Suppress("LongParameterList") // internal recovery state of download()
    private fun recoverDownload(
        name: String,
        size: Long,
        transfer: Transfer,
        before: Long,
        previousFailures: Int,
        heartbeat: () -> Unit,
        cause: XrecException,
    ): Int {
        transfer.retries++
        transfer.recovering = true
        val failures = if (transfer.offset > before) 1 else previousFailures + 1
        // The rest of the window still streams: the link is quiet again before a retry or the next command.
        drain(drainLimit(transfer.lastWindow), heartbeat)
        if (failures > maxRetries) {
            if (transfer.requests > 0) stopGets(name, size)
            val message = "Download of $name failed at ${transfer.offset}"
            throw XrecException(XrecFailure.INTEGRITY, message, cause = cause)
        }
        if (failures >= FALLBACK_FAILURES && getsWindow > XrecLimits.STOCK_GETS_WINDOW) {
            getsWindow = XrecLimits.STOCK_GETS_WINDOW
            onFallback(name, transfer.offset, getsWindow)
        }
        return failures
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
     * Ends any GETS window that still streams on the recorder (`PING` and `STAT` do not clear `gets_active`), then
     * discards its last buffered frames and its empty EOF frame up to a fresh `PONG`.
     */
    private fun stopGets(name: String, size: Long) {
        val eof = minOf(size, XrecLimits.MAX_OFFSET)
        writeBytes("\r${XrecCommands.gets(eof, 1, 1, name)}".toByteArray(Charsets.UTF_8))
        resync()
    }

    /**
     * Brings the link back to a known reply: a CR ends any partial line, then `PING`, and every reply up to its `PONG`
     * is discarded. Used after a lost reply or a refused PUT header, whose payload older firmware reads as lines (each
     * with its own `ERR` reply). Fails with [XrecFailure.TIMEOUT] when no `PONG` comes.
     */
    fun resync() {
        writeBytes(RESYNC)
        val deadline = deadline(timeouts.drainMillis)
        while (true) {
            // A reply that came before the PONG belongs to an earlier command. The bytes of a late frame are skipped,
            // and a reply can follow them on the same line.
            val line = readLine(deadline, lenient = true)
            val reply = line.substring(maxOf(0, line.lastIndexOf(XrecLimits.PREFIX)))
            if (XrecReplies.parse(reply) is XrecReply.Pong) return
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

    /**
     * One GETS window: frames until its end. Each frame must start at the expected offset; while [Transfer.recovering]
     * after a failed window, a frame of an older window (before the expected offset, or after it while no frame of this
     * window came yet) is skipped instead, within [STALE_BUDGET] bytes. A good frame that starts before the expected
     * offset and ends past it gives its tail: the last frame of a 64 KiB window is short, and a later window's frames
     * do not start where it ends.
     *
     * The recovery ends with the first good frame that no older window covered: the link is in order, so every frame
     * of the older windows came before it, and the rest of this window follows it. Until then a good frame at the
     * expected offset can be a late frame of an older window, and the repeat of this window can still stream.
     */
    private fun burst(name: String, size: Long, transfer: Transfer, sink: OutputStream, progress: (Long) -> Unit) {
        val total = minOf(getsWindow.toLong(), size - transfer.offset).toInt()
        val stop = transfer.offset + total
        send(XrecCommands.gets(transfer.offset, total, XrecLimits.GETS_CHUNK, name))
        transfer.requests++
        transfer.lastWindow = total
        val olderEnd = transfer.sentEnd
        transfer.sentEnd = maxOf(transfer.sentEnd, stop)
        var accepted = false
        var stale = 0L
        while (transfer.offset < stop) {
            val frame = readFrame(deadline(timeouts.frameMillis))
            val frameEnd = frame.offset + frame.payload.size
            val tail = transfer.recovering && frame.offset < transfer.offset && frameEnd > transfer.offset
            val old = transfer.recovering && !tail &&
                (frame.offset < transfer.offset || (frame.offset > transfer.offset && !accepted))
            if (old && stale + frame.payload.size + XrecLimits.FRAME_HEADER <= STALE_BUDGET) {
                stale += frame.payload.size + XrecLimits.FRAME_HEADER
                progress(transfer.offset) // A heartbeat: skipping a long old window is progress too.
                continue
            }
            check(name, size, transfer.offset, frame, tail)
            val skip = (transfer.offset - frame.offset).toInt()
            // A local storage failure is a plain IOException, not an XrecException.
            sink.write(frame.payload, skip, frame.payload.size - skip)
            if (frame.offset >= olderEnd) transfer.recovering = false
            transfer.offset = frameEnd
            accepted = true
            progress(transfer.offset)
        }
    }

    /** Fails unless [frame] is good and gives the bytes at [expected] ([tail]: from the middle of the frame). */
    private fun check(name: String, size: Long, expected: Long, frame: Frame, tail: Boolean) {
        when {
            frame.offset != expected && !tail ->
                throw XrecException(XrecFailure.INTEGRITY, "Frame at ${frame.offset}, expected $expected")
            frame.payload.isEmpty() ->
                throw XrecException(XrecFailure.SIZE_CHANGED, "$name ended at $expected, not at $size")
            // A frame of an old, larger window can pass the end of this one, never the end of the file.
            frame.offset + frame.payload.size > size ->
                throw XrecException(XrecFailure.INTEGRITY, "Frame longer than requested")
            Crc16CcittFalse.compute(frame.payload) != frame.crc ->
                throw XrecException(XrecFailure.INTEGRITY, "CRC error in the frame at ${frame.offset}")
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
        is XrecReply.Failed -> XrecException(
            reply.failure,
            "The recorder replied ERR,${reply.reason}",
            reply.reason,
            retryAfterMillis = reply.retryAfterMillis,
        )
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

    /**
     * The next line. A [lenient] read drops the bytes of a full buffer without a line end, except its tail (a reply
     * can start there): late binary frames can hold more than a buffer of bytes without one.
     */
    private fun readLine(deadline: Long, lenient: Boolean = false): String {
        while (true) {
            for (i in start until end) {
                if (buffer[i] == LF) {
                    val line = String(buffer, start, i - start, Charsets.UTF_8).trimEnd('\r')
                    start = i + 1
                    return line
                }
            }
            if (lenient && end - start == buffer.size) start = end - LINE_TAIL
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

    /** The longest drain after a failed window of [window] bytes: the rest of it can still stream at a slow link. */
    private fun drainLimit(window: Int): Long =
        timeouts.drainMillis + window * MILLIS_PER_SECOND / DRAIN_BYTES_PER_SECOND

    /**
     * Discards the buffered bytes and what arrives until [XrecTimeouts.quietMillis] of silence, at most [millis].
     * [heartbeat] runs after each read that brought bytes.
     */
    private fun drain(millis: Long, heartbeat: () -> Unit = {}) {
        start = 0
        end = 0
        val limit = clock() + millis
        while (clock() < limit) {
            if (readInput(scratch, 0, scratch.size, timeouts.quietMillis) == 0) return
            heartbeat()
        }
    }

    private class Transfer(var offset: Long) {
        var retries = 0
        var requests = 0

        /** The bytes the last GETS asked for. */
        var lastWindow = 0

        /** The end of the furthest window asked for: frames before it can come from an older window. */
        var sentEnd = 0L

        /** After a failed window: frames of older windows can still come, and are skipped by their offset. */
        var recovering = false
    }

    private class Frame(val offset: Long, val payload: ByteArray, val crc: Int)

    private enum class PutResult { ACCEPTED, RETRY, RESTART }

    companion object {
        /** Failed windows in a row of one download after which the client falls back to the stock window. */
        const val FALLBACK_FAILURES = 2

        /** The slowest link the drain after a failed window waits for: the rest of a 64 KiB window takes 8 s. */
        const val DRAIN_BYTES_PER_SECOND = 8_192L

        /**
         * The bytes of old frames (headers included) one window may skip: the rest of a failed window, with room for a
         * second one that the recovery itself left behind.
         */
        const val STALE_BUDGET = 2L * XrecLimits.GETS_WINDOW + 2L * XrecLimits.STOCK_GETS_WINDOW

        private const val MILLIS_PER_SECOND = 1_000L
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val DEFAULT_RETRIES = 5
        private const val BUFFER_BYTES = 4096

        /** The bytes a lenient line read keeps of a full buffer: room for the start of the longest reply. */
        private const val LINE_TAIL = 256
        private const val CR = '\r'.code.toByte()
        private val RESYNC = "\r${XrecCommands.ping()}\r".toByteArray(Charsets.US_ASCII)
        private const val LF = '\n'.code.toByte()
        private val FRAME_MAGIC = "XREC".toByteArray(Charsets.US_ASCII)
        private const val OFFSET_FIELD = 4
        private const val LENGTH_FIELD = 8
        private const val CRC_FIELD = 10
        private const val BYTE = 0xFF
        private const val BYTE_BITS = 8
    }
}
