package org.cassini.core.audio

import org.cassini.core.Failure
import org.cassini.core.requireUser

/** 16 kHz samples collected without knowing their final count. Grows by half; trimmed only when the guess was wrong. */
class PcmBuilder(expected: Int, private val heap: Heap = Heap.Jvm) {
    private var data = FloatArray(expected.coerceIn(Limits.ASR_RATE, Limits.MAX_SAMPLES))
    var size = 0
        private set

    fun append(chunk: FloatArray, count: Int) {
        requireUser(size.toLong() + count <= Limits.MAX_SAMPLES, Failure.LONG)
        if (size + count > data.size) {
            val grown = maxOf(size + count, minOf(data.size.toLong() * 3 / 2, Limits.MAX_SAMPLES.toLong()).toInt())
            requireUser(Limits.heapAllows(heap) { grown * 4L <= it }, Failure.MEMORY)
            data = data.copyOf(grown)
        }
        chunk.copyInto(data, size, 0, count)
        size += count
    }

    fun build(): FloatArray = if (size == data.size) data else data.copyOf(size)
}
