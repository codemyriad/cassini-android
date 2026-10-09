package org.cassini.core.audio

/**
 * How much longer a recording can go on: [spaceMs] until free space reaches [Limits.MIN_RECORDING_FREE_BYTES] at the
 * current bytes per second, and [capMs] until [Limits.MAX_RECORDING_MS]. The Recording screen's hint shows the
 * smaller one: "Space for about 31 h more." or "Stops at 2 hours, in about 1 h 35 min.".
 */
data class RecordingSpace(val spaceMs: Long, val capMs: Long) {
    enum class Limit { SPACE, CAP }

    /** Which limit comes first. On a tie the cap, because that stop is certain. */
    val limit: Limit get() = if (capMs <= spaceMs) Limit.CAP else Limit.SPACE

    val remainingMs: Long get() = minOf(spaceMs, capMs)

    /** [remainingMs] as the hint shows it. */
    val approx: Approx get() = approx(remainingMs)

    /** A rounded duration: [hours] and [minutes] (0..55, a multiple of 5; always 0 from 2 h). */
    data class Approx(val hours: Long, val minutes: Long) {
        val totalMinutes: Long get() = hours * MINUTES_PER_HOUR + minutes
    }

    companion object {
        private const val MS_PER_SECOND = 1000L
        private const val MS_PER_MINUTE = 60_000L
        private const val MINUTES_PER_HOUR = 60L
        private const val STEP_MINUTES = 5L
        private const val WHOLE_HOURS_FROM_MINUTES = 2 * MINUTES_PER_HOUR

        /**
         * The remaining time after [elapsedMs] of recording with [usableBytes] free, writing [bytesPerSecond]. Space
         * below the floor counts as none.
         */
        fun of(elapsedMs: Long, usableBytes: Long, bytesPerSecond: Long): RecordingSpace {
            require(bytesPerSecond > 0) { "bytesPerSecond must be positive" }
            val spare = (usableBytes - Limits.MIN_RECORDING_FREE_BYTES).coerceAtLeast(0)
            // spare * 1000 cannot overflow for any real disk (it would need 9 PB).
            val spaceMs = spare * MS_PER_SECOND / bytesPerSecond
            val capMs = (Limits.MAX_RECORDING_MS - elapsedMs).coerceAtLeast(0)
            return RecordingSpace(spaceMs, capMs)
        }

        /** Whole hours (rounded down) from 2 h; below that, minutes rounded down to 5, at least 5. */
        fun approx(ms: Long): Approx {
            val minutes = ms.coerceAtLeast(0) / MS_PER_MINUTE
            if (minutes >= WHOLE_HOURS_FROM_MINUTES) return Approx(minutes / MINUTES_PER_HOUR, 0)
            val rounded = maxOf(STEP_MINUTES, minutes / STEP_MINUTES * STEP_MINUTES)
            return Approx(rounded / MINUTES_PER_HOUR, rounded % MINUTES_PER_HOUR)
        }
    }
}
