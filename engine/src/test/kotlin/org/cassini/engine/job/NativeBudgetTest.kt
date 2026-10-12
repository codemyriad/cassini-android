package org.cassini.engine.job

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.cassini.core.Failure
import org.cassini.core.UserFacingException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NativeBudgetTest {
    @After fun budgetIsFree() {
        assertFalse("A test left the budget held", NativeBudget.isHeld)
    }

    @Test fun acquireAndReleaseOnAPlainThread() {
        NativeBudget.acquire()
        assertTrue(NativeBudget.isHeld)
        assertFalse(NativeBudget.tryAcquire(timeoutMs = 50))
        NativeBudget.release()
        assertTrue(NativeBudget.tryAcquire(timeoutMs = 50))
        NativeBudget.release()
    }

    @Test fun holdingReleasesWhenTheBlockThrows() {
        try {
            NativeBudget.holding { error("native load failed") }
            fail()
        } catch (_: IllegalStateException) {
        }
        assertFalse(NativeBudget.isHeld)
    }

    @Test fun anInterruptWhileWaitingEndsInCancelledAndKeepsTheFlag() {
        NativeBudget.acquire()
        val outcome = arrayOfNulls<Any>(2)
        val waiting = CountDownLatch(1)
        val waiter = Thread {
            waiting.countDown()
            try {
                NativeBudget.acquire()
                outcome[0] = "acquired"
                NativeBudget.release()
            } catch (error: UserFacingException) {
                outcome[0] = error.failure
            }
            outcome[1] = Thread.currentThread().isInterrupted
        }
        try {
            waiter.start()
            assertTrue(waiting.await(5, TimeUnit.SECONDS))
            Thread.sleep(100)
            waiter.interrupt()
            waiter.join(5000)
            assertFalse(waiter.isAlive)
        } finally {
            NativeBudget.release()
        }
        assertEquals(Failure.CANCELLED, outcome[0])
        assertEquals(true, outcome[1])
    }

    @Test fun anInterruptWhileTryingEndsInCancelledAndKeepsTheFlag() {
        NativeBudget.acquire()
        val outcome = arrayOfNulls<Any>(2)
        val waiting = CountDownLatch(1)
        val waiter = Thread {
            waiting.countDown()
            try {
                outcome[0] = NativeBudget.tryAcquire(timeoutMs = 60_000)
                NativeBudget.release()
            } catch (error: UserFacingException) {
                outcome[0] = error.failure
            }
            outcome[1] = Thread.currentThread().isInterrupted
        }
        try {
            waiter.start()
            assertTrue(waiting.await(5, TimeUnit.SECONDS))
            Thread.sleep(100)
            waiter.interrupt()
            waiter.join(5000)
            assertFalse(waiter.isAlive)
        } finally {
            NativeBudget.release()
        }
        assertEquals(Failure.CANCELLED, outcome[0])
        assertEquals(true, outcome[1])
    }

    @Test fun anAlreadyInterruptedThreadDoesNotTryForAFreeBudget() {
        Thread.currentThread().interrupt()
        try {
            NativeBudget.tryAcquire(timeoutMs = 50)
            NativeBudget.release()
            fail()
        } catch (error: UserFacingException) {
            assertEquals(Failure.CANCELLED, error.failure)
            assertTrue(Thread.interrupted())
        }
    }

    @Test fun anAlreadyInterruptedThreadDoesNotTakeAFreeBudget() {
        Thread.currentThread().interrupt()
        try {
            NativeBudget.acquire()
            NativeBudget.release()
            fail()
        } catch (error: UserFacingException) {
            assertEquals(Failure.CANCELLED, error.failure)
            assertTrue(Thread.interrupted())
        }
    }

    @Test fun waitersAreServedOneAtATime() = runBlocking {
        val order = mutableListOf<Int>()
        NativeBudget.acquire()
        val first = async { NativeBudget.withBudget { order += 1 } }
        val second = async { NativeBudget.withBudget { order += 2 } }
        yield()
        assertTrue(order.isEmpty())
        NativeBudget.release()
        first.await()
        second.await()
        assertEquals(listOf(1, 2), order)
    }
}
