package io.codemyriad.cassini.testing

import android.os.Looper
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext

@RunWith(RobolectricTestRunner::class)
class MainDispatchEventsTest {
    @get:Rule val mainDispatches = MainDispatchEvents()

    @Test fun aPostBeforeObservationIsDrainedOnAndroidMain() {
        var delivered = false
        Dispatchers.Main.dispatch(EmptyCoroutineContext) {
            assertSame(Looper.getMainLooper(), Looper.myLooper())
            delivered = true
        }
        mainDispatches.await(TIMEOUT_MS) { delivered }
        assertTrue(delivered)
        assertFalse(Dispatchers.Main.immediate.isDispatchNeeded(EmptyCoroutineContext))
    }

    @Test fun aPostAfterTheFailedConditionWakesTheWaiter() {
        val checked = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        var delivered = false
        try {
            val post = worker.submit {
                check(checked.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "Condition was never checked" }
                Dispatchers.Main.dispatch(EmptyCoroutineContext) { delivered = true }
            }
            mainDispatches.await(TIMEOUT_MS) {
                checked.countDown()
                delivered
            }
            post.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            assertTrue(delivered)
        } finally {
            checked.countDown()
            worker.shutdownNow()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
    }
}
