package org.cassini.core.audio

import org.cassini.core.Failure
import org.cassini.core.requireUser

/** Every length bound in one place. Audio for recognition is 16 kHz mono float: 64 KB per second. */
object Limits {
    const val MAX_RECORDING_MS = 2 * 60 * 60 * 1000L
    const val ASR_RATE = 16000
    /** One second of slack for codec padding past the stated duration. */
    const val MAX_SAMPLES = ((MAX_RECORDING_MS + 1000) * ASR_RATE / 1000).toInt()
    const val MAX_IMPORT_BYTES = 2L shl 30
    /** Cache space left free while copying an import. */
    const val MIN_FREE_BYTES = 64L shl 20
    /** A recording stops before the device runs out of space, and warns this long before [MAX_RECORDING_MS]. */
    const val MIN_RECORDING_FREE_BYTES = 200L shl 20
    const val RECORDING_WARNING_MS = 5 * 60 * 1000L

    /** PCM plus 30% headroom for decode slices, words and the resampler. */
    fun memoryAllows(durationMs: Long, availableBytes: Long) = durationMs * ASR_RATE / 1000 * 4 * 13 / 10 <= availableBytes

    fun availableHeap(runtime: Runtime = Runtime.getRuntime()) = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())

    fun requireDuration(durationMs: Long, retainPcm: Boolean = true) {
        requireUser(durationMs <= MAX_RECORDING_MS, Failure.LONG)
        if (retainPcm) requireUser(memoryAllows(durationMs, availableHeap()), Failure.MEMORY)
    }
}
