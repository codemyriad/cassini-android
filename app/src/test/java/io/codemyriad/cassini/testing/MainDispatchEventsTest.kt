package io.codemyriad.cassini.testing

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext

@RunWith(RobolectricTestRunner::class)
class MainDispatchEventsTest {
    @get:Rule val mainDispatches = MainDispatchEvents()

    @Config(sdk = [26])
    @Test
    fun aPostBeforeObservationIsDrainedOnAndroidMain() {
        var delivered = false
        Dispatchers.Main.dispatch(EmptyCoroutineContext) {
            assertSame(Looper.getMainLooper(), Looper.myLooper())
            delivered = true
        }
        assertFalse("dispatch queues the runnable", delivered)
        mainDispatches.await(TIMEOUT_MS) { delivered }
        assertTrue(delivered)
        assertFalse(Dispatchers.Main.immediate.isDispatchNeeded(EmptyCoroutineContext))
        assertSame(Dispatchers.Main.immediate, Dispatchers.Main.immediate)
    }

    @Test fun twoPostsToImmediateWakeTheWaiterAfterSeparateFailedChecks() {
        val checks = List(2) { CountDownLatch(1) }
        val worker = Executors.newSingleThreadExecutor()
        var delivered = 0
        try {
            val post = worker.submit {
                val immediate = Dispatchers.Main.immediate
                assertTrue(immediate.isDispatchNeeded(EmptyCoroutineContext))
                checks.forEachIndexed { index, checked ->
                    check(checked.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "Missing condition check $index" }
                    immediate.dispatch(EmptyCoroutineContext) {
                        assertSame(Looper.getMainLooper(), Looper.myLooper())
                        assertEquals(index, delivered)
                        delivered++
                    }
                }
            }
            mainDispatches.await(TIMEOUT_MS) {
                checks.getOrNull(delivered)?.countDown()
                delivered == checks.size
            }
            assertEquals(checks.size, delivered)
            post.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } finally {
            worker.shutdownNow()
        }
    }

    @Test fun aSnapshotApplyAfterAFailedCheckWakesTheWaiter() {
        val state = mutableStateOf(false)
        val checked = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val write = worker.submit {
                check(checked.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "Missing retained-state query" }
                state.value = true
                Snapshot.sendApplyNotifications()
            }
            mainDispatches.await(TIMEOUT_MS) {
                val current = state.value
                checked.countDown()
                current
            }
            assertTrue(state.value)
            write.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } finally {
            worker.shutdownNow()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aSchedulerDispatchAfterAFailedCheckWakesTheWaiter() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val checked = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        var delivered = false
        try {
            val post = worker.submit {
                check(checked.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "Missing scheduler query" }
                dispatcher.dispatch(EmptyCoroutineContext) { delivered = true }
            }
            mainDispatches.await(TIMEOUT_MS, scheduler) {
                scheduler.runCurrent()
                val current = delivered
                checked.countDown()
                current
            }
            assertTrue(delivered)
            post.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } finally {
            worker.shutdownNow()
        }
    }

    @Test fun aScheduledAndroidTimerRunsAtItsVirtualDeadline() {
        var delivered = false
        Handler(Looper.getMainLooper()).postDelayed({ delivered = true }, TIMER_MS)
        mainDispatches.await(TIMEOUT_MS, TestCoroutineScheduler()) { delivered }
        assertTrue(delivered)
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
        const val TIMER_MS = 300L
    }
}
