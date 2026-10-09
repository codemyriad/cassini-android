package org.cassini.core.audio

/**
 * What a running recording does next, decided once a second from the time recorded, the free space and the rate the
 * file grows. Stops come first: the 2 h cap ([Limits.MAX_RECORDING_MS]), then free space below
 * [Limits.MIN_RECORDING_FREE_BYTES]. Then a warning for whichever limit comes first: [Decision.StorageLow] when 15 min
 * of space or less are left, [Decision.NearTimeLimit] from 5 min before the cap ([Limits.RECORDING_WARNING_MS]).
 */
object RecordingPolicy {
    /** Nominal file growth of a platform Opus capture at 48 kb/s, used until the file has grown for 10 s. */
    const val OPUS_BYTES_PER_SECOND = 6000L

    /** Nominal file growth of an AAC capture at 32 kb/s. */
    const val AAC_BYTES_PER_SECOND = 4000L

    /** Space left (as recording time) at or below which the storage warning shows. */
    const val STORAGE_LOW_MS = 15 * 60 * 1000L

    sealed interface Decision {
        data object Continue : Decision

        /** About [minutes] of space left, rounded as [RecordingSpace.approx] rounds (down to 5, at least 5). */
        data class StorageLow(val minutes: Long) : Decision

        /** The cap stops the recording in [msLeft]. */
        data class NearTimeLimit(val msLeft: Long) : Decision

        data object StopForSpace : Decision

        data object StopAtTimeLimit : Decision
    }

    fun decide(elapsedMs: Long, usableBytes: Long, bytesPerSecond: Long): Decision {
        val space = RecordingSpace.of(elapsedMs, usableBytes, bytesPerSecond)
        val storageLow = space.spaceMs <= STORAGE_LOW_MS
        val nearCap = space.capMs <= Limits.RECORDING_WARNING_MS
        return when {
            elapsedMs >= Limits.MAX_RECORDING_MS -> Decision.StopAtTimeLimit
            usableBytes < Limits.MIN_RECORDING_FREE_BYTES -> Decision.StopForSpace
            // Both close: warn about the one that stops the recording first.
            storageLow && (!nearCap || space.limit == RecordingSpace.Limit.SPACE) ->
                Decision.StorageLow(RecordingSpace.approx(space.spaceMs).totalMinutes)
            nearCap -> Decision.NearTimeLimit(space.capMs)
            else -> Decision.Continue
        }
    }
}
