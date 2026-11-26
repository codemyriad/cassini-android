package io.codemyriad.cassini.core.recorder

import java.io.IOException

/**
 * Why an XREC operation failed. The values with a [reason] are the device's `+XREC:ERR,<reason>` codes; the others
 * come from the client.
 */
enum class XrecFailure(val reason: String?) {
    /** The command needs an authenticated session. */
    LOCKED("LOCKED"),

    /** The AUTH response was wrong: the device has another phone's key, or the stored key is stale. */
    AUTH("AUTH"),

    /** The device has no binding key: pair first. */
    UNBOUND("UNBOUND"),

    /** AUTH without a fresh CHAL. */
    NO_CHALLENGE("NOCHAL"),

    /** The device's 60 s pairing window is not open. */
    PAIRING_CLOSED("PAIRCLOSED"),

    /** Wrong PIN (5 failures close the window). */
    WRONG_PIN("PIN"),
    BAD_KEY("BADKEY"),

    /** The device could not save the key on its card. */
    KEY_NOT_SAVED("SAVE"),
    NOT_FOUND("NOENT"),
    NO_CARD("NOSD"),
    BAD_NAME("NAME"),

    /** The file is being recorded. */
    BUSY("BUSY"),

    /**
     * `LATER,<ms>`: the recorder records, and the command waits for the end of the recording (a card write, or any
     * transfer during a stereo recording). Ask again after [XrecException.retryAfterMillis]; nothing changed.
     */
    LATER("LATER"),
    DELETE_FAILED("DEL"),
    CRC("CRC"),
    OFFSET("OFF"),
    BAD_ARGUMENT("ARG"),
    BAD_ARGUMENTS("ARGS"),
    UNSUPPORTED("UNSUP"),
    CARD_IO("SDIO"),
    UNKNOWN_COMMAND("CMD"),
    LINE_TOO_LONG("LINE"),
    RTC_RANGE("RTCRNG"),
    RTC_FAILED("RTC"),
    NO_UPLOAD("NOPUT"),

    /** A device error code this client does not know. */
    OTHER_DEVICE_ERROR(null),

    /** The device rejected the `REC` upload target: its firmware is too old for transcript upload. Do not retry. */
    FIRMWARE_TOO_OLD(null),

    /** No reply in time. */
    TIMEOUT(null),

    /** The link closed. */
    DISCONNECTED(null),

    /** An unexpected or unreadable reply. */
    PROTOCOL(null),

    /** Frames kept failing their offset or CRC check, or an upload kept failing, after all retries. */
    INTEGRITY(null),

    /** The file ended before its listed size: it changed on the device. List again. */
    SIZE_CHANGED(null),
    ;

    companion object {
        /** The failure of the device reason [reason]; a reason with arguments (`LATER,<ms>`) by its first field. */
        fun ofReason(reason: String): XrecFailure {
            val code = reason.substringBefore(',')
            return entries.firstOrNull { it.reason == code } ?: OTHER_DEVICE_ERROR
        }
    }
}

/**
 * An XREC operation failed with [failure]; [reason] is the device's error code when it sent one, and
 * [retryAfterMillis] the wait of an [XrecFailure.LATER] (null when the device named none).
 */
class XrecException(
    val failure: XrecFailure,
    message: String,
    val reason: String? = failure.reason,
    cause: Throwable? = null,
    val retryAfterMillis: Long? = null,
) : IOException(message, cause) {
    /** True when the client may try the same step again on the same link. */
    val transient: Boolean
        get() = failure == XrecFailure.TIMEOUT || failure == XrecFailure.PROTOCOL || failure == XrecFailure.INTEGRITY
}
