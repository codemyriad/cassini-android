package io.codemyriad.cassini.engine.job

import io.codemyriad.cassini.core.Failure
import io.codemyriad.cassini.core.UserFacingException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

class NativeBudgetTest {
    @After fun budgetIsFree() {
        assertFalse("A test left the budget held", NativeBudget.isHeld)
    }

    @Test fun acquireAndReleaseOnAPlainThread() {
        NativeBudget.acquire()
        assertTrue(NativeBudget.isHeld)
        assertFalse(NativeBudget.tryAcquire(timeoutMs = 0))
        NativeBudget.release()
        assertTrue(NativeBudget.tryAcquire(timeoutMs = 0))
        NativeBudget.release()
    }

    @OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class)
    @Test
    fun aContendedAttemptExpiresOnlyWhenItsVirtualDeadlineIsAdvanced() = runTest {
        val scheduled = CountDownLatch(1)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val observed = object : CoroutineDispatcher(), Delay by dispatcher {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                dispatcher.dispatch(context, block)
                scheduled.countDown()
            }
        }
        val mutex = ContendedMutex()
        val permit = NativePermit(mutex, observed)
        permit.acquire()
        val result = FutureTask { permit.tryAcquire(timeoutMs = 50) }
        val waiter = Thread(result)
        try {
            waiter.start()
            assertTrue(scheduled.await(5, TimeUnit.SECONDS))
            testScheduler.runCurrent()
            assertEquals("the contended lock was entered", 0L, mutex.waiting.count)
            assertFalse("no expiry before virtual time advances", result.isDone)
            testScheduler.advanceTimeBy(50)
            testScheduler.runCurrent()
            waiter.join(5000)
            assertFalse("the timed attempt completed", waiter.isAlive)
            assertFalse(result.get(5, TimeUnit.SECONDS))
            assertTrue("timeout leaves the original holder intact", permit.isHeld)
        } finally {
            permit.release()
            waiter.interrupt()
            testScheduler.runCurrent()
            waiter.join(5000)
        }
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
        val mutex = ContendedMutex()
        val permit = NativePermit(mutex)
        permit.acquire()
        val outcome = arrayOfNulls<Any>(2)
        val waiter = Thread {
            try {
                permit.acquire()
                outcome[0] = "acquired"
                permit.release()
            } catch (error: UserFacingException) {
                outcome[0] = error.failure
            }
            outcome[1] = Thread.currentThread().isInterrupted
        }
        try {
            waiter.start()
            assertTrue("entered contended Mutex.lock", mutex.waiting.await(5, TimeUnit.SECONDS))
            waiter.interrupt()
            waiter.join(5000)
            assertFalse(waiter.isAlive)
        } finally {
            permit.release()
            waiter.interrupt()
            waiter.join(5000)
        }
        assertFalse("the interrupted waiter released no permit of its own", permit.isHeld)
        assertEquals(Failure.CANCELLED, outcome[0])
        assertEquals(true, outcome[1])
    }

    @Test fun anInterruptWhileTryingEndsInCancelledAndKeepsTheFlag() {
        val mutex = ContendedMutex()
        val permit = NativePermit(mutex)
        permit.acquire()
        val outcome = arrayOfNulls<Any>(2)
        val waiter = Thread {
            try {
                val acquired = permit.tryAcquire(timeoutMs = 60_000)
                outcome[0] = acquired
                if (acquired) permit.release()
            } catch (error: UserFacingException) {
                outcome[0] = error.failure
            }
            outcome[1] = Thread.currentThread().isInterrupted
        }
        try {
            waiter.start()
            assertTrue("entered contended Mutex.lock", mutex.waiting.await(5, TimeUnit.SECONDS))
            waiter.interrupt()
            waiter.join(5000)
            assertFalse(waiter.isAlive)
        } finally {
            permit.release()
            waiter.interrupt()
            waiter.join(5000)
        }
        assertFalse("the interrupted waiter released no permit of its own", permit.isHeld)
        assertEquals(Failure.CANCELLED, outcome[0])
        assertEquals(true, outcome[1])
    }

    @Test fun anAlreadyInterruptedThreadDoesNotTryForAFreeBudget() {
        Thread.currentThread().interrupt()
        try {
            NativeBudget.tryAcquire(timeoutMs = 0)
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

    private class ContendedMutex(private val inner: Mutex = Mutex()) : Mutex by inner {
        val waiting = CountDownLatch(1)

        override suspend fun lock(owner: Any?) {
            check(inner.isLocked) { "The fixture must hold the permit before the waiter enters" }
            waiting.countDown()
            inner.lock(owner)
        }
    }
}
