package io.codemyriad.cassini.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
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

    @Test fun anIoExceptionFromCancellationIsReportedAsCancellation() = runTest {
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val observed = CompletableDeferred<Throwable>()
        val input = object : InputStream() {
            override fun read(): Int {
                entered.countDown()
                check(closed.await(5, TimeUnit.SECONDS))
                throw IOException("Provider closed")
            }
            override fun close() { closed.countDown() }
        }
        val read = launch(Dispatchers.IO) {
            try {
                input.useCancellable { it.read() }
            } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
                observed.complete(error)
            }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            read.cancelAndJoin()
            assertTrue(observed.await() is CancellationException)
        } finally {
            input.close()
        }
    }

    @Test fun normalIoErrorsRemainProviderFailures() = runTest {
        val failure = IOException("Provider failure")
        val input = object : InputStream() { override fun read(): Int = throw failure }
        try {
            input.useCancellable { it.read() }
            error("Expected provider failure")
        } catch (error: IOException) {
            assertEquals(failure.javaClass, error.javaClass)
            assertEquals(failure.message, error.message)
        }
    }
}
