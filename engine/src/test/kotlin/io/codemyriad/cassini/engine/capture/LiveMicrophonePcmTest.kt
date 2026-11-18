package io.codemyriad.cassini.engine.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveMicrophonePcmTest {
    @Test fun overflowPreservesTheCaptureClockAndDoesNotOverwriteQueuedPcm() {
        val tap = LiveMicrophonePcm(capacity = 1)
        val reused = shortArrayOf(1, 2)
        tap.offer(0, reused, 2)
        reused[0] = 7
        tap.offer(2, reused, 2)

        assertArrayEquals(shortArrayOf(1, 2), tap.blocks.tryReceive().getOrThrow().pcm16)
        tap.offer(4, reused, 2)
        tap.finish()

        assertEquals(4L, tap.blocks.tryReceive().getOrThrow().firstFrame)
        assertEquals(6L, tap.endFrame)
        assertTrue(tap.blocks.tryReceive().isClosed)
    }
}
