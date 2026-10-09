package org.cassini.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.cassini.data.db.offMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class NoteLocksTest {
    private val locks = NoteLocks()

    @Test fun theSameNoteAlwaysGetsTheSameLock() {
        val id = "0b6f8f0e-8d9e-4c1a-9a43-1f2d3c4b5a69"
        assertSame(locks.lockFor(id), locks.lockFor(String(id.toCharArray())))
    }

    @Test fun blockingVariantRefusesTheMainThread() {
        val error = runCatching { locks.withNoteLockBlocking("a") { } }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals(7, offMain { locks.withNoteLockBlocking("a") { 7 } })
    }

    @Test fun suspendVariantRunsOnIoAndHoldsTheLock() = runBlocking {
        val seen = locks.withNoteLock("a", Dispatchers.IO) {
            Threads.isMain() to locks.lockFor("a").isHeldByCurrentThread
        }
        assertFalse(seen.first)
        assertTrue(seen.second)
        assertFalse(locks.lockFor("a").isLocked)
    }

    @Test fun suspendAndBlockingCallersExcludeEachOther() = runBlocking {
        var counter = 0
        val rounds = 2_000
        val pool = Executors.newFixedThreadPool(2)
        val done = CountDownLatch(2)
        repeat(2) {
            pool.execute {
                repeat(rounds) { locks.withNoteLockBlocking("n") { counter = counter + 1 } }
                done.countDown()
            }
        }
        val jobs = List(4) {
            launch(Dispatchers.Default) {
                repeat(rounds) { locks.withNoteLock("n", Dispatchers.IO) { counter = counter + 1 } }
            }
        }
        jobs.forEach { it.join() }
        assertTrue(done.await(30, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(6 * rounds, counter)
    }
}
