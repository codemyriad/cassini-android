package io.codemyriad.cassini.recorder

import java.time.Duration
import java.time.LocalDateTime

/** When a sync sets the recorder's clock: the recorder has no time zone, so it keeps the phone's local time. */
object RecorderClock {
    private const val TOLERANCE_SECONDS = 5L

    /** The largest difference that a sync accepts without setting the clock. */
    val TOLERANCE: Duration = Duration.ofSeconds(TOLERANCE_SECONDS)

    /** True when the recorder's [device] time (null: unreadable) differs from the phone's [phone] time too much. */
    fun needsSet(device: LocalDateTime?, phone: LocalDateTime): Boolean =
        device == null || Duration.between(device, phone).abs() > TOLERANCE
}

/**
 * The waits of "Auto sync". The recorder accepts a connection only while its user has it in Bluetooth phone mode, so
 * most attempts fail: each failure in a row doubles the wait, up to [MAX_MS]. A sync that connected but had nothing to
 * copy or send is idle: each idle sync in a row doubles the wait after it too, up to [MAX_MS], so a recorder left in
 * Bluetooth phone mode is not checked every few minutes.
 */
object AutoSyncBackoff {
    /** The wait after a sync that copied or sent something. */
    const val INTERVAL_MS = 3 * 60_000L

    /** The first wait after a failed connection. */
    const val FIRST_RETRY_MS = 60_000L

    const val MAX_MS = 15 * 60_000L

    private const val MAX_DOUBLINGS = 10

    /**
     * The wait before the next attempt after [failures] failed attempts in a row, or, when the last one connected
     * ([failures] 0), after [idle] idle syncs in a row.
     */
    fun delayMs(failures: Int, idle: Int = 0): Long = when {
        failures > 0 -> doubled(FIRST_RETRY_MS, failures - 1)
        else -> doubled(INTERVAL_MS, idle)
    }

    private fun doubled(base: Long, times: Int): Long =
        (base shl times.coerceIn(0, MAX_DOUBLINGS)).coerceAtMost(MAX_MS)
}

/**
 * The waits between the attempts to send a new transcript to the recorder at once (whatever "Auto sync" is): 30 s
 * after the first failed attempt, doubled after each failure in a row, up to [MAX_MS]. The recorder answers only in its
 * Bluetooth phone mode, so a transcript usually waits for the user to turn that mode on; the short first waits let it
 * go soon after.
 */
object TranscriptPushBackoff {
    const val FIRST_RETRY_MS = 30_000L
    const val MAX_MS = 5 * 60_000L

    private const val MAX_DOUBLINGS = 10

    /** The wait after [failures] failed attempts in a row (1 or more). */
    fun delayMs(failures: Int): Long =
        (FIRST_RETRY_MS shl (failures - 1).coerceIn(0, MAX_DOUBLINGS)).coerceAtMost(MAX_MS)
}

/**
 * When the app offers a transcript to a recorder whose firmware refused the `REC` target. The firmware name in `PING`
 * does not change when the recorder gets the firmware that takes transcripts, so the app asks again once a day, and at
 * once when the firmware name changes.
 */
object TranscriptUploadProbe {
    const val RETRY_MS = 24 * 60 * 60_000L

    /**
     * True when an upload may run: the recorder [advertised] the target (`rec=1` in PONG), or the firmware [firmware]
     * never refused, or it refused at [refusedAt], long ago.
     */
    fun mayUpload(
        refusedFirmware: String?,
        refusedAt: Long?,
        firmware: String,
        now: Long,
        advertised: Boolean = false,
    ): Boolean =
        advertised || refusedFirmware != firmware || refusedAt == null || now - refusedAt !in 0 until RETRY_MS
}
