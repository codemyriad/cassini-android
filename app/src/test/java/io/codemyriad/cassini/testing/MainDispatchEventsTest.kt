package io.codemyriad.cassini.testing

import android.os.Looper
import kotlinx.coroutines.Dispatchers
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

    private companion object {
        const val TIMEOUT_MS = 20_000L
    }
}
