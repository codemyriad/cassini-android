package org.cassini.core.asr

object ProcessingSpeed {
    /** Larger is faster: 60 s of audio processed in 30 s is 2x realtime. */
    fun realtime(audioMs: Long, elapsedMs: Long): Double? =
        if (audioMs > 0 && elapsedMs > 0) audioMs.toDouble() / elapsedMs else null
}
