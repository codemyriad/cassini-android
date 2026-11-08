package io.codemyriad.cassini.whistle

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import androidx.test.core.app.ApplicationProvider
import io.codemyriad.cassini.core.models.WhistleCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class WhistleWorkerTest {
    @Test
    fun failedBindUnbindsAndPreservesOriginalFailure() = offMain {
        val context = RefusingContext()
        val admission = Semaphore(1)
        val error = assertThrows(IllegalStateException::class.java) { worker(context, admission) }
        assertEquals("Whistle worker could not bind", error.message)
        assertEquals("cleanup failed", error.suppressed.single().message)
        assertTrue(context.unbound)
        assertEquals(1, admission.availablePermits())
    }

    @Test
    fun unavailablePermitHasDeadlineAndNeverBinds() = offMain {
        val context = RefusingContext()
        val error = assertThrows(IllegalStateException::class.java) { worker(context, Semaphore(0)) }
        assertEquals("Whistle worker is busy", error.message)
        assertTrue(!context.bound && !context.unbound)
    }

    @Test
    fun interruptedAdmissionRestoresInterrupt() = offMain {
        val context = RefusingContext()
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedException::class.java) { worker(context, Semaphore(0)) }
            assertTrue(Thread.currentThread().isInterrupted)
            assertTrue(!context.bound && !context.unbound)
        } finally {
            Thread.interrupted()
        }
    }

    private fun worker(context: Context, admission: Semaphore) =
        WhistleWorker(context, 16000, {}, admission, 0) { WhistleCapability.ELIGIBLE }

    private fun offMain(test: () -> Unit) {
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.submit(test).get(10, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    private class RefusingContext : ContextWrapper(ApplicationProvider.getApplicationContext()) {
        var bound = false
        var unbound = false
        override fun getApplicationContext(): Context = this
        override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean {
            bound = true
            return false
        }
        override fun unbindService(connection: ServiceConnection) {
            unbound = true
            error("cleanup failed")
        }
    }
}
