package org.cassini.core.audio

/**
 * What a running recording does next, decided once a second from the free space, the size of the capture file and
 * the rate the file grows. A recording has no time limit: it runs until the user stops it, until the free space
 * falls below [Limits.MIN_RECORDING_FREE_BYTES] ([Decision.StopForSpace]), or until an AAC capture file reaches
 * [AAC_MAX_FILE_BYTES] ([Decision.StopAtFileLimit]). Before the storage stop, [Decision.StorageLow] warns when 15 min
 * of space or less are left.
 */
object RecordingPolicy {
    /** Nominal file growth of a platform Opus capture at 48 kb/s, used until the file has grown for 10 s. */
    const val OPUS_BYTES_PER_SECOND = 6000L

    /** Nominal file growth of an AAC capture at 32 kb/s. */
    const val AAC_BYTES_PER_SECOND = 4000L

    /** Space left (as recording time) at or below which the storage warning shows. */
    const val STORAGE_LOW_MS = 15 * 60 * 1000L

    /**
     * The size at which an AAC capture (API 26-28, and phones without an Opus encoder) stops and saves: 1 GiB, about
     * 74 hours at 32 kb/s. Two platform limits are above it. MediaRecorder writes its MP4 file with 32-bit chunk
     * offsets (AOSP `MPEG4Writer`), so the platform ends such a file at 4 GiB. It also keeps the index of the file in
     * memory until the stop: 4 bytes for each 21.3 ms AAC frame, about 50 MB at this size. The Opus capture writes
     * Ogg pages as it goes and has no such limit.
     */
    const val AAC_MAX_FILE_BYTES = 1L shl 30

    sealed interface Decision {
        data object Continue : Decision

        /** About [minutes] of space left, rounded as [RecordingSpace.approx] rounds (down to 5, at least 5). */
        data class StorageLow(val minutes: Long) : Decision

        data object StopForSpace : Decision

        /** The capture file reached the size limit of its format. */
        data object StopAtFileLimit : Decision
    }

    /**
     * The decision with [usableBytes] free, a capture file of [fileBytes] that may grow to [maxFileBytes] (no limit
     * by default), and a file that grows by [bytesPerSecond]. The storage warning counts only the free space.
     */
    fun decide(
        usableBytes: Long,
        bytesPerSecond: Long,
        fileBytes: Long = 0,
        maxFileBytes: Long = Long.MAX_VALUE,
    ): Decision {
        val space = RecordingSpace.of(usableBytes, bytesPerSecond)
        return when {
            usableBytes < Limits.MIN_RECORDING_FREE_BYTES -> Decision.StopForSpace
            fileBytes >= maxFileBytes -> Decision.StopAtFileLimit
            space.spaceMs <= STORAGE_LOW_MS -> Decision.StorageLow(space.approx.totalMinutes)
            else -> Decision.Continue
        }
    }
}
