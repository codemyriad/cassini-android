package org.cassini.android

/** Every length bound in one place. Audio for recognition is 16 kHz mono float: 64 KB per second. */
internal object Limits {
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

    fun requireDuration(durationMs: Long) {
        requireUser(durationMs <= MAX_RECORDING_MS, Failure.LONG)
        requireUser(memoryAllows(durationMs, availableHeap()), Failure.MEMORY)
    }
}

/** mm:ss, or h:mm:ss from one hour. */
internal fun clock(ms: Long): String {
    val s = ms.coerceAtLeast(0) / 1000
    return if (s >= 3600) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    else String.format(java.util.Locale.ROOT, "%02d:%02d", s / 60, s % 60)
}

/** 16 kHz samples collected without knowing their final count. Grows by half; trimmed only when the guess was wrong. */
internal class PcmBuilder(expected: Int) {
    private var data = FloatArray(expected.coerceIn(Limits.ASR_RATE, Limits.MAX_SAMPLES))
    var size = 0
        private set

    fun append(chunk: FloatArray, count: Int) {
        requireUser(size.toLong() + count <= Limits.MAX_SAMPLES, Failure.LONG)
        if (size + count > data.size) {
            val grown = maxOf(size + count, minOf(data.size.toLong() * 3 / 2, Limits.MAX_SAMPLES.toLong()).toInt())
            requireUser(grown * 4L <= Limits.availableHeap(), Failure.MEMORY)
            data = data.copyOf(grown)
        }
        chunk.copyInto(data, size, 0, count)
        size += count
    }

    fun build(): FloatArray = if (size == data.size) data else data.copyOf(size)
}
