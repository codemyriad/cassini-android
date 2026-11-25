package io.codemyriad.cassini.recorder

import io.codemyriad.cassini.core.recorder.XrecException
import io.codemyriad.cassini.core.recorder.XrecFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.Flushable
import java.io.IOException

class ThenFlushingTest {
    private var flushes = 0
    private val full = Flushable {
        flushes++
        throw IOException("No space left on device")
    }
    private val good = Flushable { flushes++ }

    @Test fun aFailedFlushDoesNotHideTheFailureOfTheDownload() {
        val later = XrecException(XrecFailure.LATER, "The recorder replied ERR,LATER,30000", retryAfterMillis = 30_000)
        try {
            thenFlushing(full) { throw later }
            fail("Expected LATER")
        } catch (e: XrecException) {
            assertSame(later, e)
            assertTrue(e.suppressed.single() is IOException)
        }
        assertEquals(1, flushes)
    }

    @Test fun theBytesOfAFailedDownloadAreFlushed() {
        try {
            thenFlushing(good) { throw XrecException(XrecFailure.DISCONNECTED, "The link closed") }
            fail("Expected DISCONNECTED")
        } catch (e: XrecException) {
            assertEquals(XrecFailure.DISCONNECTED, e.failure)
            assertTrue(e.suppressed.isEmpty())
        }
        assertEquals(1, flushes)
    }

    @Test fun aFailedFlushAfterAGoodDownloadFails() {
        try {
            thenFlushing(full) { 42 }
            fail("Expected the flush failure")
        } catch (e: IOException) {
            assertEquals("No space left on device", e.message)
        }
    }

    @Test fun aGoodDownloadIsFlushedAndGivesItsResult() {
        assertEquals(42, thenFlushing(good) { 42 })
        assertEquals(1, flushes)
    }
}
