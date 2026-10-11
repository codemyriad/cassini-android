package org.cassini.core.audio

import org.cassini.core.Failure
import org.cassini.core.requireUser

/** Every length bound in one place. Audio for recognition is 16 kHz mono float: 64 KB per second. */
object Limits {
    /**
     * The longest audio that one transcription reads. 0.1.0 also stopped a recording here (2 h); a recording now has
     * no time limit. 36 h keeps every 16 kHz sample position of [MAX_SAMPLES] below 2^31.
     */
    const val MAX_TRANSCRIBE_MS = 36 * 60 * 60 * 1000L
    const val ASR_RATE = 16000

    /** One second of slack for codec padding past the stated duration. */
    const val MAX_SAMPLES = ((MAX_TRANSCRIBE_MS + 1000) * ASR_RATE / 1000).toInt()

    /** The largest file that an import copies: the largest WAV file (its sizes are 32-bit). A safety bound only. */
    const val MAX_IMPORT_BYTES = 4L shl 30

    /** Cache space left free while copying an import. */
    const val MIN_FREE_BYTES = 64L shl 20

    /** A recording stops and saves before the free space of the device falls below this floor. */
    const val MIN_RECORDING_FREE_BYTES = 200L shl 20

    /** PCM plus 30% headroom for decode slices, words and the resampler. */
    fun memoryAllows(durationMs: Long, availableBytes: Long) = durationMs * ASR_RATE / 1000 * 4 * 13 / 10 <= availableBytes

    fun availableHeap(runtime: Runtime = Runtime.getRuntime()) = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())

    fun requireDuration(durationMs: Long, retainPcm: Boolean = true) {
        requireUser(durationMs <= MAX_TRANSCRIBE_MS, Failure.LONG)
        if (retainPcm) requireUser(memoryAllows(durationMs, availableHeap()), Failure.MEMORY)
    }
}
