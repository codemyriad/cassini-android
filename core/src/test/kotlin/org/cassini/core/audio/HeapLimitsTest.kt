package org.cassini.core.audio

import org.cassini.core.Failure
import org.cassini.core.UserFacingException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

/**
 * Garbage that earlier work left on the heap does not refuse an import with MEMORY: a heap that looks too short is
 * measured again after a garbage collection. The API 29 device check once failed `importsCompressedAudioOnTheSameClock`
 * this way, on an emulator heap full of the earlier tests' garbage.
 */
class HeapLimitsTest {
    /** A heap that shows [free] bytes, and [afterCollect] bytes once collected. */
    private class FakeHeap(private var free: Long, private val afterCollect: Long) : Heap {
        var collections = 0
        override fun available() = free
        override fun collect() {
            collections++
            free = afterCollect
        }
    }

    @Test fun garbageOnTheRealHeapDoesNotRefuseAnImport() {
        val garbage = leaveGarbage(Runtime.getRuntime().maxMemory() / 2)
        val shown = Limits.availableHeap()
        // More than the heap shows now, and less than it has once the garbage is collected.
        val durationMs = durationNeeding(shown + garbage / 2)
        assertFalse("the garbage counts as used before a collection", Limits.memoryAllows(durationMs, shown))
        Limits.requireDuration(durationMs)
    }

    @Test fun aHeapThatFitsIsNotCollected() {
        val heap = FakeHeap(free = 100L shl 20, afterCollect = 100L shl 20)
        Limits.requireDuration(60_000, heap = heap)
        assertEquals(0, heap.collections)
    }

    @Test fun aShortHeapIsMeasuredAgainAfterOneCollection() {
        val heap = FakeHeap(free = 1L shl 20, afterCollect = 100L shl 20)
        Limits.requireDuration(60_000, heap = heap)
        assertEquals(1, heap.collections)
    }

    @Test fun aHeapStillShortAfterACollectionRefuses() {
        val heap = FakeHeap(free = 1L shl 20, afterCollect = 2L shl 20)
        try {
            Limits.requireDuration(60_000, heap = heap)
            fail()
        } catch (e: UserFacingException) {
            assertEquals(Failure.MEMORY, e.failure)
        }
        assertEquals(1, heap.collections)
    }

    @Test fun pcmGrowsIntoTheHeapThatACollectionFrees() {
        val heap = FakeHeap(free = 0, afterCollect = 100L shl 20)
        val pcm = PcmBuilder(Limits.ASR_RATE, heap)
        pcm.append(FloatArray(2 * Limits.ASR_RATE), 2 * Limits.ASR_RATE)
        assertEquals(2 * Limits.ASR_RATE, pcm.size)
        assertEquals(1, heap.collections)
    }

    private companion object {
        const val CHUNK = 8L shl 20

        /** Fills [bytes] of the heap with arrays that are garbage when this returns; nothing has collected them yet. */
        fun leaveGarbage(bytes: Long): Long {
            val chunks = ArrayList<ByteArray>()
            var left = bytes
            while (left > 0) {
                val size = minOf(left, CHUNK)
                chunks += ByteArray(size.toInt())
                left -= size
            }
            return chunks.sumOf { it.size.toLong() }
        }

        /** The duration whose PCM and headroom ([Limits.memoryAllows]) take [bytes]. */
        fun durationNeeding(bytes: Long) = bytes * 10 / 13 / 4 * 1000 / Limits.ASR_RATE
    }
}
