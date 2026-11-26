@file:Suppress("MagicNumber") // The fixed limits and field positions of the open_fw XREC protocol.

package io.codemyriad.cassini.core.recorder

import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** The limits of the open_fw XREC protocol (recorder firmware `xrec_proto.c`). */
object XrecLimits {
    /** Every command and reply line starts with this prefix. */
    const val PREFIX = "+XREC:"

    /** The longest command line the device accepts, without its CR; a longer one gives `ERR,LINE`. */
    const val MAX_LINE = 191

    /** The largest GETS frame payload and PUT chunk the device accepts. */
    const val MAX_CHUNK = 640

    /**
     * The bytes the app asks for with one GETS command (its window) on open_fw: the device takes any total and paces
     * the frames through its TX ring, so one request streams up to 64 KiB without a round trip between frames.
     */
    const val GETS_WINDOW = 65_536

    /**
     * The window of the stock firmware (its PONG names [STOCK_FIRMWARE_MARK]), and of the fallback after repeated
     * failures with [GETS_WINDOW]: 6 frames of 640 bytes, strictly one request at a time.
     */
    const val STOCK_GETS_WINDOW = 3840

    /** The PONG firmware field of the stock firmware contains this; open_fw names itself `openfw_v<n>`. */
    const val STOCK_FIRMWARE_MARK = "pen133"

    /** The payload size the app asks for in each GETS frame. */
    const val GETS_CHUNK = 640

    /** The PUT chunk size the app sends: one chunk in flight must fit the device's 2048-byte receive ring. */
    const val PUT_CHUNK = 600

    /**
     * The size of the first PUT chunk of an upload: the CWX1 header. Firmware without the `REC` target reads the
     * payload of the refused header as command lines, so the probe sends little.
     */
    const val PROBE_CHUNK = 64

    /** The bytes of a GETS frame header: "XREC", u32le offset, u16le length, u16le CRC. */
    const val FRAME_HEADER = 12

    /**
     * STAT `media` while the device records. Without the STAT field `rec_idx` (older open_fw), downloads from /record
     * wait while it shows.
     */
    const val MEDIA_RECORDING = 33

    /**
     * The GETS window while the device records (docs/BT_SYNC_SPEED_AND_RECORDING.md A1): the device caps the GETS rate
     * then, and STAT `pace_ms` is the time one window of this size takes at that cap.
     */
    const val RECORDING_GETS_WINDOW = 16_384

    /** A WAV file of this size or smaller holds no audio. */
    const val EMPTY_WAV_BYTES = 44L

    /** The largest file offset a frame or PUT chunk can address. */
    const val MAX_OFFSET = 0xFFFF_FFFFL
}

/** The file name rules of the firmware (`is_safe_filename`) and of PUT targets. */
object XrecNames {
    private const val MAX_NAME_BYTES = 63
    private const val MAX_PUT_NAME = 30
    private const val FORBIDDEN = "/\\:*?\"<>|"
    const val SIDECAR_EXTENSION = ".cwx"
    private const val WAV_EXTENSION = ".wav"

    /** True when the firmware accepts [name] as a file name in /record. */
    fun isSafe(name: String): Boolean {
        val bytes = name.toByteArray(Charsets.UTF_8)
        val sized = bytes.isNotEmpty() && bytes.size < MAX_NAME_BYTES
        val dots = name == "." || name == ".."
        return sized && !dots && bytes.none(::forbidden) && !name.endsWith('.') && !name.endsWith(' ')
    }

    private fun forbidden(byte: Byte): Boolean {
        val value = byte.toInt() and 0xFF
        return value < 0x20 || value == 0x7F || value.toChar() in FORBIDDEN
    }

    /** True when [name] is a valid PUT target name: safe, ASCII only, at most 30 characters. */
    fun isPutName(name: String): Boolean = isSafe(name) && name.length <= MAX_PUT_NAME && name.all { it.code < 0x80 }

    /** True when [name] is a valid `REC` target: a non-hidden PUT name with a stem before `.cwx` (any case). */
    fun isSidecarName(name: String): Boolean =
        isPutName(name) && !name.startsWith('.') && name.length > SIDECAR_EXTENSION.length && isSidecar(name)

    /** True when [name] ends in `.wav` in any case. */
    fun isWav(name: String): Boolean = name.endsWith(WAV_EXTENSION, ignoreCase = true)

    /** True when [name] ends in `.cwx` in any case. */
    fun isSidecar(name: String): Boolean = name.endsWith(SIDECAR_EXTENSION, ignoreCase = true)

    /**
     * The transcript sidecar next to the recording [recordingName]: the stem plus `.cwx`, as the player looks for it.
     * Null when the name is not a valid `REC` target (too long or not ASCII).
     */
    fun sidecarFor(recordingName: String): String? {
        val dot = recordingName.lastIndexOf('.')
        val stem = if (dot > 0) recordingName.substring(0, dot) else recordingName
        return (stem + SIDECAR_EXTENSION).takeIf(::isSidecarName)
    }
}

/** The target of a PUT upload. */
sealed interface PutTarget {
    /** The header field after the CRC, before the `:`. */
    val field: String

    /** `/record/<name>`, a transcript sidecar committed by PUTEND (newer firmware only). */
    data class Record(val name: String) : PutTarget {
        init {
            require(XrecNames.isSidecarName(name)) { "Not a sidecar name: $name" }
        }

        override val field: String get() = "REC,$name"
    }

    /** `/cassini/<name>`. */
    data class Cassini(val name: String) : PutTarget {
        init {
            require(XrecNames.isPutName(name)) { "Not a PUT name: $name" }
        }

        override val field: String get() = "CAS,$name"
    }
}

/** The command lines the app sends, without the CR terminator (the PUT header ends with its `:`). */
@Suppress("TooManyFunctions") // One builder for each command.
object XrecCommands {
    private const val PIN_DIGITS = 6
    private val prefix = XrecLimits.PREFIX

    fun ping(): String = "${prefix}PING"

    fun pair(pin: String, key: ByteArray): String {
        require(pin.length == PIN_DIGITS && pin.all { it in '0'..'9' }) { "The PIN has 6 digits" }
        require(key.size == XrecAuth.KEY_BYTES) { "The key has 32 bytes" }
        return "${prefix}PAIR,$pin,${XrecAuth.toHex(key)}"
    }

    fun challenge(): String = "${prefix}CHAL"

    fun auth(responseHex: String): String {
        val valid = responseHex.length == 64 && responseHex.all { it in '0'..'9' || it in 'a'..'f' }
        require(valid) { "The response has 64 lower-case hex digits" }
        return "${prefix}AUTH,$responseHex"
    }

    fun stat(): String = "${prefix}STAT"

    fun rtc(): String = "${prefix}RTC"

    /** Sets the device clock; the weekday is 0 for Monday to 6 for Sunday. */
    fun setRtc(time: LocalDateTime): String = with(time) {
        "${prefix}RTC,$year,$monthValue,$dayOfMonth,$hour,$minute,$second,${dayOfWeek.value - 1}"
    }

    fun list(): String = "${prefix}LS"

    fun gets(offset: Long, total: Int, chunk: Int, name: String): String {
        require(offset in 0..XrecLimits.MAX_OFFSET) { "Offset out of range" }
        require(total > 0 && chunk in 1..XrecLimits.MAX_CHUNK) { "Bad total or chunk" }
        return checked("${prefix}GETS,$offset,$total,$chunk,${safe(name)}")
    }

    fun delete(name: String): String = checked("${prefix}DEL,${safe(name)}")

    fun putHeader(offset: Long, length: Int, crc: Int, target: PutTarget): String {
        require(offset in 0..XrecLimits.MAX_OFFSET) { "Offset out of range" }
        require(length in 1..XrecLimits.MAX_CHUNK && crc in 0..0xFFFF) { "Bad length or CRC" }
        return checked("${prefix}PUT,$offset,$length,$crc,${target.field}:")
    }

    fun putEnd(): String = "${prefix}PUTEND"

    /** Reports how many items ([pending], 0..65535) still need recorder sync/transcription, with an optional [etaSeconds]. */
    fun sync(pending: Int, etaSeconds: Int = 0): String {
        require(pending in 0..MAX_SYNC_FIELD && etaSeconds in 0..MAX_SYNC_FIELD) { "Sync status out of range" }
        return if (etaSeconds > 0) "${prefix}SYNC,$pending,$etaSeconds" else "${prefix}SYNC,$pending"
    }

    private const val MAX_SYNC_FIELD = 0xFFFF

    private fun safe(name: String): String = name.also { require(XrecNames.isSafe(it)) { "Not a safe file name: $it" } }

    private fun checked(line: String): String = line.also {
        require(it.toByteArray(Charsets.UTF_8).size <= XrecLimits.MAX_LINE) { "Command longer than 191 bytes" }
    }
}

/** A `+XREC:` reply line, parsed. */
sealed interface XrecReply {
    /**
     * `PONG,<firmware>,<protocol>,bound=,auth=,pair=[,rec=1][,sync=1]`. [recordTarget] is true when the recorder
     * advertises the `REC,<stem>.cwx` upload target (`rec=1`); [syncStatusSupported] is true when it accepts
     * `+XREC:SYNC,<pending>` (`sync=1`) to shut down background Bluetooth as soon as `pending` reaches 0.
     */
    data class Pong(
        val firmware: String,
        val protocol: String,
        val bound: Boolean,
        val authenticated: Boolean,
        val pairingOpen: Boolean,
        val recordTarget: Boolean = false,
        val syncStatusSupported: Boolean = false,
    ) : XrecReply

    /** `CHAL,<32 hex>`. */
    data class Challenge(val nonceHex: String) : XrecReply

    /**
     * `STAT,<bat %>,<mV>,<charge 0/1/2>,<vbus 0/1>,<media>,<rec s>,<free MB>,<total MB>[,<rec_idx>,<pace_ms>]`.
     *
     * [recordingIndex] and [paceMillis] are the fields 9 and 10 of the open_fw that syncs while it records (null on
     * older firmware): the `NNNN` of the `REC_NNNN.wav` being recorded (0: none), and the pause between two GETS
     * windows of [XrecLimits.RECORDING_GETS_WINDOW] bytes while it records (0: no pacing; 30000 during a stereo
     * recording, which allows no transfer).
     */
    data class Status(
        val batteryPercent: Int,
        val millivolts: Int,
        val charge: Int,
        val usbPower: Boolean,
        val media: Int,
        val recordingSeconds: Long,
        val freeMb: Long,
        val totalMb: Long,
        val recordingIndex: Long? = null,
        val paceMillis: Long? = null,
    ) : XrecReply {
        val recording: Boolean get() = media == XrecLimits.MEDIA_RECORDING

        /** The firmware names the file it records ([recordingIndex] present): it can sync while it records. */
        val namesActiveFile: Boolean get() = recordingIndex != null
    }

    /** `RTC,YYYY-MM-DD HH:MM:SS,<weekday 0=Monday>`. [time] is null when the device clock holds no valid date. */
    data class Clock(val text: String, val time: LocalDateTime?, val weekday: Int) : XrecReply

    /** `F,<size>,<name>`: one file of /record. The name may contain commas. */
    data class Entry(val size: Long, val name: String) : XrecReply

    /** `END`: the end of an LS listing. */
    data object End : XrecReply

    /** `OK,<tag>[,<fields>]`. */
    data class Ok(val tag: String, val fields: List<String>) : XrecReply

    /** `ERR,<reason>`; [reason] keeps the arguments of `LATER,<ms>`. */
    data class Failed(val reason: String) : XrecReply {
        val failure: XrecFailure get() = XrecFailure.ofReason(reason)

        /** The `<ms>` of `LATER,<ms>`, or null. */
        val retryAfterMillis: Long?
            get() = reason.takeIf { failure == XrecFailure.LATER }?.substringAfter(',', "")?.toLongOrNull()
                ?.takeIf { it >= 0 }
    }

    /** Any other `+XREC:` line, or one of the above with fields the app cannot read. */
    data class Other(val line: String) : XrecReply
}

/** Parses reply lines. */
@Suppress("ReturnCount") // Each parser gives null at its first unreadable field.
object XrecReplies {
    private val clockFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")

    /** The reply of [line] (without CR/LF), or null when it does not start with `+XREC:` (the app ignores it). */
    fun parse(line: String): XrecReply? {
        if (!line.startsWith(XrecLimits.PREFIX)) return null
        val body = line.substring(XrecLimits.PREFIX.length)
        val tag = body.substringBefore(',')
        val rest = if (body.contains(',')) body.substringAfter(',') else null
        val reply = if (rest == null) {
            XrecReply.End.takeIf { tag == "END" }
        } else {
            parsers[tag]?.invoke(rest)
        }
        return reply ?: XrecReply.Other(line)
    }

    private val parsers: Map<String, (String) -> XrecReply?> = mapOf(
        "PONG" to ::pong,
        "CHAL" to ::challenge,
        "STAT" to ::status,
        "RTC" to ::clock,
        "F" to ::entry,
        "OK" to ::ok,
        "ERR" to XrecReply::Failed,
    )

    private fun challenge(rest: String): XrecReply.Challenge? =
        rest.takeIf { it.length == 32 && it.all(::isHex) }?.let { XrecReply.Challenge(it.lowercase()) }

    private fun ok(rest: String): XrecReply.Ok {
        val fields = rest.split(',')
        return XrecReply.Ok(fields.first(), fields.drop(1))
    }

    private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    private fun pong(rest: String): XrecReply.Pong? {
        val fields = rest.split(',')
        if (fields.size < 2) return null
        val flags = fields.drop(2).mapNotNull { field ->
            field.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
        }.toMap()
        return XrecReply.Pong(
            firmware = fields[0],
            protocol = fields[1],
            bound = flags["bound"] == "1",
            authenticated = flags["auth"] == "1",
            pairingOpen = flags["pair"] == "1",
            recordTarget = flags["rec"] == "1",
            syncStatusSupported = flags["sync"] == "1",
        )
    }

    /** The first 8 fields must be numbers; the fields 9 and 10 are read when they are numbers, later ones ignored. */
    private fun status(rest: String): XrecReply.Status? {
        val fields = rest.split(',')
        if (fields.size < 8) return null
        val values = fields.take(8).map { it.toLongOrNull() ?: return null }
        return XrecReply.Status(
            batteryPercent = values[0].toInt(),
            millivolts = values[1].toInt(),
            charge = values[2].toInt(),
            usbPower = values[3] != 0L,
            media = values[4].toInt(),
            recordingSeconds = values[5],
            freeMb = values[6],
            totalMb = values[7],
            recordingIndex = fields.getOrNull(8)?.toLongOrNull()?.takeIf { it >= 0 },
            paceMillis = fields.getOrNull(9)?.toLongOrNull()?.takeIf { it >= 0 },
        )
    }

    private fun clock(rest: String): XrecReply.Clock? {
        if (!rest.contains(',')) return null
        val text = rest.substringBeforeLast(',')
        val weekday = rest.substringAfterLast(',').toIntOrNull() ?: return null
        val time = try {
            LocalDateTime.parse(text, clockFormat)
        } catch (_: DateTimeParseException) {
            null
        } catch (_: DateTimeException) {
            null
        }
        return XrecReply.Clock(text, time, weekday)
    }

    private fun entry(rest: String): XrecReply.Entry? {
        val parts = rest.split(',', limit = 2)
        if (parts.size != 2 || parts[1].isEmpty()) return null
        val size = parts[0].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        return XrecReply.Entry(size, parts[1])
    }
}
