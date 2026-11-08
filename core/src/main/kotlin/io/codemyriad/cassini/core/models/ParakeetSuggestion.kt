package io.codemyriad.cassini.core.models

/** D78: existing memory warnings, a measured-peak-plus-headroom 6 GB total-RAM floor, and the real installation plan. */
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

    // 2 x Android RSS high-water (1,097,424 KiB) + 3 GiB OS/other-app reserve, rounded up to whole GB.
    // The short model-only emulator run is a lower bound, not long-meeting or phone acceptance.
    const val MIN_TOTAL_BYTES = 6_000_000_000L
}
