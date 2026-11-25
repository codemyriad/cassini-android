// A test double that mirrors the firmware's C handlers, early returns and compound checks included.
@file:Suppress("ReturnCount", "ComplexCondition", "CyclomaticComplexMethod", "TooManyFunctions")

package io.codemyriad.cassini.core.recorder

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Collections

/**
 * A recorder in BT phone mode, as `open_fw/src/xrec_proto.c` behaves, behind a pair of pipes. [clientInput] and
 * [clientOutput] are the phone's side of the link. Its own thread feeds the bytes the phone writes through the same
 * byte-level state machine as `xrec_on_rx`: CR-terminated lines, and a PUT header that ends at its `:` and then exactly
 * `len` raw payload bytes. File names are case-insensitive, as on the exFAT card.
 *
 * [recordTarget] true is the firmware of branch fix/xrec-cwx-sidecar: it has the `REC` target, discards the payload of a
 * header it refuses once the length is known, answers `ERR,NOENT` to a `REC` upload without its `.wav` (at offset 0 and
 * at PUTEND), answers a repeated PUTEND again, and DEL of a recording also deletes its sidecar. [recordTarget] false is
 * the older firmware: it knows only `CAS`, a refused header leaves the payload to the line parser, and a repeated
 * PUTEND gets `ERR,NOPUT`.
 *
 * GETS takes any total, as open_fw does, and streams the whole window before it reads the next command (the worst
 * case of the device's TX ring: a new GETS does not cut the old window short). GETS frames are clipped to `mtu - 12`
 * bytes. Faults: [dropFrames] and [corruptFrames] (a GETS frame offset, once),
 * [dropPutAcks] (the reply of a written PUT chunk, once), [corruptPutChunks] (a received PUT chunk, once),
 * [disconnectAtFrame] (the link closes instead of sending that GETS frame, once), [dropPutEndAck] (the reply of a
 * committed PUTEND, once), [unreadable] (GETS of these names gives `ERR,SDIO`), [refusedNames] (a `REC` header with
 * one of these names gives `ERR,NAME`), and the hooks [beforeGets], [beforePut] and [beforePutEnd]. [reconnect] opens
 * a new link to the same device.
 */
class FakeRecorder(
    @Volatile var key: ByteArray? = null,
    @Volatile var pairingPin: String? = null,
    @Volatile var mtu: Int = 0,
    @Volatile var recording: Boolean = false,
    @Volatile var recordTarget: Boolean = true,
    /** The firmware field of PONG; the stock firmware names `pen133`. */
    @Volatile var firmware: String = "openfw_v5",
) : Closeable {
    val files: MutableMap<String, ByteArray> = Collections.synchronizedMap(LinkedHashMap())
    val cassini: MutableMap<String, ByteArray> = Collections.synchronizedMap(LinkedHashMap())
    val dropFrames: MutableSet<Long> = Collections.synchronizedSet(HashSet())
    val corruptFrames: MutableSet<Long> = Collections.synchronizedSet(HashSet())
    val dropPutAcks: MutableSet<Long> = Collections.synchronizedSet(HashSet())
    val corruptPutChunks: MutableSet<Long> = Collections.synchronizedSet(HashSet())
    val unreadable: MutableSet<String> = Collections.synchronizedSet(HashSet())
    val refusedNames: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /** The device closes the link instead of sending the GETS frame at this offset (once). */
    @Volatile var disconnectAtFrame: Long? = null

    /** The device commits the next PUTEND and sends no reply (once). */
    @Volatile var dropPutEndAck = false

    /** Runs on the device thread before a GETS is handled, with its file name. */
    @Volatile var beforeGets: ((String) -> Unit)? = null

    /** Runs on the device thread before a received PUT chunk is handled, with its offset. */
    @Volatile var beforePut: ((Long) -> Unit)? = null

    /** Runs on the device thread before PUTEND is handled. */
    @Volatile var beforePutEnd: (() -> Unit)? = null

    /** The command lines the device processed, in order (PUT headers without their payload). */
    val commands: MutableList<String> = Collections.synchronizedList(ArrayList())

    /** The PUT chunks the device wrote to the staged file (an idempotent resend does not count). */
    @Volatile var putWrites = 0
        private set

    @Volatile var authenticated = false
        private set

    private val toDevice = PipedInputStream(PIPE_BYTES)
    val clientOutput: OutputStream = PipedOutputStream(toDevice)
    private val fromDevice = PipedOutputStream()
    val clientInput: InputStream = PipedInputStream(fromDevice, PIPE_BYTES)

    private val nonce = ByteArray(XrecAuth.NONCE_BYTES) { (0xA0 + it).toByte() }
    private var challenged = false
    private var pairFails = 0

    private val line = ByteArrayOutputStream()
    private var overflow = false
    private var put: PutHeader? = null
    private val putBuffer = ByteArrayOutputStream()
    private var staged: Staged? = null
    private var last: PutHeader? = null

    /** The payload bytes of a refused header that the device still eats unparsed. */
    private var discard = 0

    /** The size of the last committed upload, until the next PUT header: a repeated PUTEND answers it again. */
    private var committed: Int? = null

    private class PutHeader(val offset: Long, val length: Int, val crc: Int, val target: String, val name: String)

    private class Staged(val target: String, val name: String) {
        val data = ByteArrayOutputStream()
    }

    private val thread = Thread(::run, "fake-recorder").apply {
        isDaemon = true
        start()
    }

    /**
     * A new link to this device after this one closed: the key, the PIN window, the faults that did not fire and the
     * files stay; the session (authentication, a staged upload) starts again, as after a disconnect.
     */
    fun reconnect(): FakeRecorder {
        close()
        val next = FakeRecorder(key, pairingPin, mtu, recording, recordTarget, firmware)
        synchronized(files) { next.files.putAll(files) }
        synchronized(cassini) { next.cassini.putAll(cassini) }
        next.dropFrames += dropFrames
        next.corruptFrames += corruptFrames
        next.dropPutAcks += dropPutAcks
        next.corruptPutChunks += corruptPutChunks
        next.unreadable += unreadable
        next.refusedNames += refusedNames
        next.beforeGets = beforeGets
        next.disconnectAtFrame = disconnectAtFrame
        next.dropPutEndAck = dropPutEndAck
        next.beforePut = beforePut
        next.beforePutEnd = beforePutEnd
        return next
    }

    override fun close() {
        clientOutput.close() // Ends the device thread's read.
        thread.join(JOIN_MILLIS)
        fromDevice.close()
    }

    private fun run() {
        try {
            while (true) {
                val b = toDevice.read()
                if (b < 0) break
                receive(b)
            }
        } catch (_: IOException) {
            // The phone closed the link.
        }
    }

    private fun receive(b: Int) {
        if (discard > 0) {
            discard--
            return
        }
        val header = put
        if (header != null) {
            putBuffer.write(b)
            if (putBuffer.size() == header.length) {
                put = null
                putComplete(header, putBuffer.toByteArray())
            }
            return
        }
        when {
            b == LF -> Unit
            b == CR -> endLine()
            overflow -> Unit
            b == COLON && line.size() >= PUT_MIN && line.toString(Charsets.ISO_8859_1).startsWith("+XREC:PUT,") -> {
                val text = line.toString(Charsets.UTF_8)
                line.reset()
                startPut(text)
            }
            line.size() < XrecLimits.MAX_LINE -> line.write(b)
            else -> overflow = true
        }
    }

    private fun endLine() {
        if (overflow) {
            overflow = false
            line.reset()
            err("LINE")
            return
        }
        val text = line.toString(Charsets.UTF_8)
        line.reset()
        if (text.isNotEmpty()) process(text)
    }

    private fun process(text: String) {
        if (!text.startsWith("+XREC:")) return
        commands += text
        val cmd = text.removePrefix("+XREC:")
        when {
            cmd == "PING" -> pong()
            cmd.startsWith("PAIR,") -> pair(cmd.removePrefix("PAIR,"))
            cmd == "CHAL" -> {
                challenged = true
                reply("+XREC:CHAL,${XrecAuth.toHex(nonce)}")
            }
            cmd.startsWith("AUTH,") -> auth(cmd.removePrefix("AUTH,"))
            !authenticated -> err("LOCKED")
            cmd == "STAT" -> reply("+XREC:STAT,87,3990,0,0,${if (recording) "33,12" else "16,0"},1500,7600")
            cmd == "RTC" -> reply("+XREC:RTC,2026-11-25 10:11:12,2")
            cmd.startsWith("RTC,") -> reply("+XREC:OK,RTC")
            cmd == "LS" -> {
                synchronized(files) { files.forEach { (name, data) -> reply("+XREC:F,${data.size},$name") } }
                reply("+XREC:END")
            }
            cmd.startsWith("GETS,") -> gets(cmd.removePrefix("GETS,"))
            cmd.startsWith("DEL,") -> delete(cmd.removePrefix("DEL,"))
            cmd == "PUTEND" -> putEnd()
            else -> err("CMD")
        }
    }

    private fun pong() {
        val flags = "bound=${flag(key != null)},auth=${flag(authenticated)},pair=${flag(pairingPin != null)}"
        reply("+XREC:PONG,$firmware,v5,$flags")
    }

    private fun pair(args: String) {
        val pin = pairingPin ?: return err("PAIRCLOSED")
        if (args.length < 7 || args[6] != ',' || args.substring(0, 6) != pin) {
            if (++pairFails >= MAX_PAIR_FAILS) pairingPin = null
            return err("PIN")
        }
        val hex = args.substring(7)
        if (hex.length != 64 || hex.any { Character.digit(it, 16) < 0 }) return err("BADKEY")
        key = XrecAuth.fromHex(hex)
        authenticated = true
        pairingPin = null
        reply("+XREC:OK,PAIR")
    }

    private fun auth(hex: String) {
        val bound = key ?: return err("UNBOUND")
        if (!challenged) return err("NOCHAL")
        challenged = false
        if (hex.lowercase() != XrecAuth.response(bound, XrecAuth.toHex(nonce))) return err("AUTH")
        authenticated = true
        reply("+XREC:OK,AUTH")
    }

    private fun gets(args: String) {
        val parts = args.split(',', limit = 4)
        val offset = parts.getOrNull(0)?.toLongOrNull()
        val total = parts.getOrNull(1)?.toLongOrNull()
        var chunk = parts.getOrNull(2)?.toIntOrNull()
        if (parts.size != 4 || offset == null || total == null || total == 0L || chunk == null || chunk !in 1..640) {
            return err("ARGS")
        }
        if (mtu > 12) chunk = minOf(chunk, mtu - 12) else if (mtu > 0) chunk = 1
        val name = parts[3]
        beforeGets?.invoke(name)
        if (!XrecNames.isSafe(name)) return err("NAME")
        if (name in unreadable) return err("SDIO")
        val data = find(name)?.let(files::get) ?: return err("NOENT")
        var at = offset
        var remaining = total
        while (remaining > 0) {
            val want = minOf(remaining, chunk.toLong()).toInt()
            val got = if (at >= data.size) 0 else minOf(want.toLong(), data.size - at).toInt()
            val from = at.toInt().coerceAtMost(data.size)
            val payload = data.copyOfRange(from, from + got)
            sendFrame(at, payload)
            at += got
            remaining -= got
            if (got < want) break
        }
    }

    private fun sendFrame(offset: Long, payload: ByteArray) {
        if (disconnectAtFrame == offset) {
            disconnectAtFrame = null
            fromDevice.close()
            throw IOException("The device dropped the link") // Ends the device thread, as a lost radio link does.
        }
        if (dropFrames.remove(offset)) return
        val crc = Crc16CcittFalse.compute(payload)
        val body = payload.copyOf()
        if (corruptFrames.remove(offset) && body.isNotEmpty()) body[0] = (body[0].toInt() xor 0x5A).toByte()
        val frame = ByteArray(12 + body.size)
        "XREC".toByteArray().copyInto(frame)
        for (i in 0 until 4) frame[4 + i] = (offset shr (8 * i)).toByte()
        frame[8] = body.size.toByte()
        frame[9] = (body.size shr 8).toByte()
        frame[10] = crc.toByte()
        frame[11] = (crc shr 8).toByte()
        body.copyInto(frame, 12)
        send(frame)
    }

    private fun delete(name: String) {
        if (!XrecNames.isSafe(name)) return err("NAME")
        val found = find(name)
        if (recording && found != null && found == files.keys.lastOrNull()) return err("BUSY")
        if (found == null || files.remove(found) == null) return err("DEL")
        if (recordTarget && XrecNames.isWav(found)) XrecNames.sidecarFor(found)?.let(::find)?.let(files::remove)
        reply("+XREC:OK,DEL")
    }

    /**
     * `try_start_put_header`: the header ends at its `:`. A bad offset or length leaves the payload to the line parser;
     * a later refusal does too on the old firmware, while the new one eats the payload unparsed.
     */
    private fun startPut(text: String) {
        val fields = text.removePrefix("+XREC:PUT,").split(',', limit = 4)
        val offset = fields.getOrNull(0)?.toLongOrNull()
        val length = fields.getOrNull(1)?.toIntOrNull()
        if (offset == null || length == null || length !in 1..640) return err("ARG")
        committed = null
        val crc = fields.getOrNull(2)?.toIntOrNull()
        val target = fields.getOrNull(3)
        if (crc == null || crc > 0xFFFF || target == null) return refuse(length, "ARG")
        val header = when {
            target == "OTA" || target == "EXP" -> return refuse(length, "UNSUP")
            target.startsWith("CAS,") -> {
                val name = target.removePrefix("CAS,")
                if (!XrecNames.isPutName(name)) return refuse(length, "NAME")
                PutHeader(offset, length, crc, "CAS", name)
            }
            recordTarget && target.startsWith("REC,") -> {
                val name = target.removePrefix("REC,")
                if (!XrecNames.isSidecarName(name) || name in refusedNames) return refuse(length, "NAME")
                PutHeader(offset, length, crc, "REC", name)
            }
            else -> return refuse(length, "ARG")
        }
        commands += "+XREC:PUT,$offset,$length,$crc,${header.target},${header.name}:"
        putBuffer.reset()
        put = header
    }

    /** `put_refuse` of the new firmware; the old one replies only, and its line parser gets the payload. */
    private fun refuse(length: Int, reason: String) {
        err(reason)
        if (recordTarget) discard = length
    }

    /** The name of the file of /record that is [name] in any case, as the card's lookups find it. */
    private fun find(name: String): String? = synchronized(files) { files.keys.firstOrNull { it.equals(name, true) } }

    /** `rec_wav_exists`: the recording of the sidecar [cwx]. */
    private fun recordingExists(cwx: String): Boolean = find(cwx.dropLast(SIDECAR_EXTENSION) + ".wav") != null

    /** `handle_put_complete`. */
    private fun putComplete(header: PutHeader, received: ByteArray) {
        beforePut?.invoke(header.offset)
        if (!authenticated) return err("LOCKED")
        val payload = received.copyOf()
        if (corruptPutChunks.remove(header.offset)) payload[0] = (payload[0].toInt() xor 0x5A).toByte()
        if (Crc16CcittFalse.compute(payload) != header.crc) return err("CRC")
        val current = staged
        val previous = last
        if (current != null && previous != null && current.target == header.target && current.name == header.name &&
            previous.offset == header.offset && previous.length == header.length && previous.crc == header.crc
        ) {
            return ack(header)
        }
        if (header.offset == 0L) {
            staged = null
            last = null
            if (recordTarget && header.target == "REC" && !recordingExists(header.name)) return err("NOENT")
            staged = Staged(header.target, header.name)
        } else if (current == null || current.target != header.target || current.name != header.name ||
            header.offset != current.data.size().toLong()
        ) {
            return err("OFF")
        }
        val target = checkNotNull(staged)
        target.data.write(payload)
        putWrites++
        last = header
        ack(header)
    }

    private fun ack(header: PutHeader) {
        if (dropPutAcks.remove(header.offset)) return
        reply("+XREC:OK,PUT,${header.offset},${header.length}")
    }

    private fun putEnd() {
        beforePutEnd?.invoke()
        val current = staged
        if (current == null) {
            val total = committed ?: return err("NOPUT")
            return reply("+XREC:OK,PUTEND,$total")
        }
        staged = null
        last = null
        val bytes = current.data.toByteArray()
        if (current.target == "REC") {
            if (recordTarget && !recordingExists(current.name)) return err("NOENT")
            synchronized(files) {
                find(current.name)?.let(files::remove)
                files[current.name] = bytes
            }
        } else {
            cassini[current.name] = bytes
        }
        if (recordTarget) committed = bytes.size
        if (dropPutEndAck) {
            dropPutEndAck = false
            return
        }
        reply("+XREC:OK,PUTEND,${bytes.size}")
    }

    private fun flag(value: Boolean) = if (value) "1" else "0"

    private fun err(reason: String) = reply("+XREC:ERR,$reason")

    private fun reply(text: String) = send("$text\r\n".toByteArray(Charsets.UTF_8))

    private fun send(bytes: ByteArray) {
        fromDevice.write(bytes)
        fromDevice.flush()
    }

    private companion object {
        const val PIPE_BYTES = 256 * 1024
        const val JOIN_MILLIS = 2_000L
        const val CR = '\r'.code
        const val LF = '\n'.code
        const val COLON = ':'.code
        const val PUT_MIN = 14
        const val MAX_PAIR_FAILS = 5
        const val SIDECAR_EXTENSION = 4
    }
}
