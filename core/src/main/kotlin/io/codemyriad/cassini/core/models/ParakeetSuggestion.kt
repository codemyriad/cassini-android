package io.codemyriad.cassini.core.models

/** D78: existing memory warnings, a conservative 4 GB total-RAM floor, and the real installation plan. */
object ParakeetSuggestion {
    @Suppress("LongParameterList") // Independent capability, memory and disk facts; no platform objects in :core.
    fun eligible(
        runtimeSupported: Boolean,
        lowRam: Boolean,
        lowMemory: Boolean,
        totalMemoryBytes: Long,
        freeBytes: Long,
        requiredFreeBytes: Long,
    ): Boolean = runtimeSupported && !lowRam && !lowMemory && totalMemoryBytes >= MIN_TOTAL_BYTES &&
        requiredFreeBytes > 0 && freeBytes >= requiredFreeBytes

    const val MIN_TOTAL_BYTES = 4_000_000_000L
}
