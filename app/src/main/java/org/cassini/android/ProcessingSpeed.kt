package org.cassini.android

internal object ProcessingSpeed {
    /** Larger is faster: 60 s of audio processed in 30 s is 2x realtime. */
    fun realtime(audioMs: Long, elapsedMs: Long): Double? =
        if (audioMs > 0 && elapsedMs > 0) audioMs.toDouble() / elapsedMs else null

    /** Time left at the pace so far; null until a second of work has covered some audio, when a pace means something. */
    fun remainingMs(doneMs: Long, totalMs: Long, elapsedMs: Long): Long? =
        if (doneMs <= 0 || elapsedMs < 1000 || totalMs < doneMs) null else (totalMs - doneMs) * elapsedMs / doneMs
}
