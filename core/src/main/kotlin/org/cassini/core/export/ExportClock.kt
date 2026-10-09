package org.cassini.core.export

import java.util.Locale

/** The time stamps of the exports (features.md sections 0 and 9). Negative times are written as 0. */
object ExportClock {
    private const val SECOND_MS = 1000L
    private const val PER_MINUTE = 60L
    private const val MINUTE_MS = PER_MINUTE * SECOND_MS
    private const val HOUR_MS = PER_MINUTE * MINUTE_MS

    /**
     * The TXT clock of [ms]: `mm:ss` when [durationMs] is below 1 h, else `h:mm:ss`; seconds are floored. A time of
     * 1 h or more also uses `h:mm:ss`, so a duration that is too short never gives a minute count over 59.
     */
    fun clock(ms: Long, durationMs: Long): String {
        val t = ms.coerceAtLeast(0)
        return if (durationMs >= HOUR_MS || t >= HOUR_MS) {
            String.format(Locale.ROOT, "%d:%02d:%02d", t / HOUR_MS, minutes(t), seconds(t))
        } else {
            String.format(Locale.ROOT, "%02d:%02d", minutes(t), seconds(t))
        }
    }

    /** The SRT time stamp `HH:MM:SS,mmm`. */
    fun srt(ms: Long): String = stamp(ms, ',')

    /** The WebVTT time stamp `HH:MM:SS.mmm`. */
    fun vtt(ms: Long): String = stamp(ms, '.')

    private fun minutes(t: Long): Long = t / MINUTE_MS % PER_MINUTE

    private fun seconds(t: Long): Long = t / SECOND_MS % PER_MINUTE

    private fun stamp(ms: Long, separator: Char): String {
        val t = ms.coerceAtLeast(0)
        val hms = String.format(Locale.ROOT, "%02d:%02d:%02d", t / HOUR_MS, minutes(t), seconds(t))
        return hms + separator + String.format(Locale.ROOT, "%03d", t % SECOND_MS)
    }
}
