package org.cassini.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class SerialWriterTest {
    @Test fun writesRunInOrderAndFlushWaitsForThem() {
        val writer = SerialWriter("test-writer")
        val seen = Collections.synchronizedList(mutableListOf<Int>())
        for (index in 0 until 50) writer.submit { Thread.sleep(1); seen += index }
        assertTrue(writer.flush(5_000))
        assertEquals((0 until 50).toList(), seen.toList())
    }

    @Test fun aFailedWriteDoesNotStopLaterOnes() {
        val writer = SerialWriter("test-writer")
        var failed: Exception? = null
        var after = false
        writer.submit(onError = { failed = it }) { throw IllegalStateException("disk") }
        writer.submit { after = true }
        assertTrue(writer.flush(5_000))
        assertTrue(failed is IllegalStateException)
        assertTrue(after)
    }

    @Test fun submitReturnsWithoutWaitingForTheWrite() {
        val writer = SerialWriter("test-writer")
        val started = System.nanoTime()
        writer.submit { Thread.sleep(300) }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 200)
        assertTrue(writer.flush(5_000))
    }

    @Test fun flushFromAWriteDoesNotWaitForItself() {
        val writer = SerialWriter("test-writer")
        var inner = false
        writer.submit { inner = writer.flush(5_000) }
        assertTrue(writer.flush(5_000))
        assertTrue(inner)
    }
}
