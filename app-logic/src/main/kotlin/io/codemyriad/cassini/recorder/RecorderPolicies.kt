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
 * most attempts fail: each failure in a row doubles the wait, up to [MAX_MS].
 */
object AutoSyncBackoff {
    /** The wait after a sync that connected. */
    const val INTERVAL_MS = 3 * 60_000L

    /** The first wait after a failed connection. */
    const val FIRST_RETRY_MS = 60_000L

    const val MAX_MS = 15 * 60_000L

    private const val MAX_DOUBLINGS = 10

    /** The wait before the next attempt after [failures] failed attempts in a row (0: the last one connected). */
    fun delayMs(failures: Int): Long = if (failures <= 0) {
        INTERVAL_MS
    } else {
        (FIRST_RETRY_MS shl (failures - 1).coerceAtMost(MAX_DOUBLINGS)).coerceAtMost(MAX_MS)
    }
}
