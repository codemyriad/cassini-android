package org.cassini.core.audio

/**
 * How much longer a recording can go on: [spaceMs] until the free space reaches [Limits.MIN_RECORDING_FREE_BYTES]
 * at the current bytes per second. A recording has no time limit, so the Recording screen's hint shows only this:
 * "Space for about 31 h more.". When the capture file has a size limit of its own (the MP4 file of the AAC capture,
 * [RecordingPolicy.AAC_MAX_FILE_BYTES]), [of] gives the smaller of the two.
 */
data class RecordingSpace(val spaceMs: Long) {
    /** [spaceMs] as the hint shows it. */
    val approx: Approx get() = approx(spaceMs)

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
         * The remaining time with [usableBytes] free, writing [bytesPerSecond]. Space below the floor counts as none.
         * [fileRoomBytes] is what the capture file can still take before its own size limit; no limit by default.
         */
        fun of(usableBytes: Long, bytesPerSecond: Long, fileRoomBytes: Long = Long.MAX_VALUE): RecordingSpace {
            require(bytesPerSecond > 0) { "bytesPerSecond must be positive" }
            val free = (usableBytes - Limits.MIN_RECORDING_FREE_BYTES).coerceAtLeast(0)
            val spare = minOf(free, fileRoomBytes.coerceAtLeast(0))
            // spare * 1000 cannot overflow for any real disk (it would need 9 PB).
            return RecordingSpace(spare * MS_PER_SECOND / bytesPerSecond)
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
