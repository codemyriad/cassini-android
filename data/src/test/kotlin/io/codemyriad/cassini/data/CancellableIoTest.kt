package io.codemyriad.cassini.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class CancellableIoTest {
    @Test fun cancellingABlockedProviderReadClosesItsPipe() = runTest {
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val releasedByClose = AtomicBoolean(false)
        val input = object : InputStream() {
            override fun read(): Int {
                entered.countDown()
                val notified = closed.await(5, TimeUnit.SECONDS)
                releasedByClose.set(notified)
                check(notified) { "The blocked pipe was not closed" }
                return -1
            }
            override fun close() { closed.countDown() }
        }
        val read = launch(Dispatchers.IO) { input.useCancellable { it.read() } }
        try {
            assertTrue("The provider read started", entered.await(5, TimeUnit.SECONDS))
            read.cancelAndJoin()
            assertTrue("Cancellation released the blocked read", releasedByClose.get())
            assertTrue("Cancellation closed the stream", closed.count == 0L)
        } finally {
            input.close()
        }
    }
}
