package io.codemyriad.cassini.whistle

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import android.os.Messenger
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

    @Test
    fun oomIpcReplyThrowsOutOfMemoryErrorAndReleasesPermit() = offMain {
        val serviceThread = HandlerThread("FakeWhistleOomService").apply { start() }
        try {
            val serviceMessenger = Messenger(
                Handler(serviceThread.looper) { msg ->
                    val reply = Message.obtain(null, WhistleProtocol.ERROR).apply {
                        data = WhistleProtocol.errorBundle(
                            "Whistle worker out of memory",
                            WhistleProtocol.ERROR_CODE_OUT_OF_MEMORY,
                        )
                    }
                    msg.replyTo.send(reply)
                    true
                },
            )
            val context = FakeBoundContext(serviceMessenger)
            val admission = Semaphore(1)
            val error = assertThrows(OutOfMemoryError::class.java) {
                WhistleWorker(context, 16000, {}, admission, 5) { WhistleCapability.ELIGIBLE }
            }
            assertEquals("Whistle worker out of memory", error.message)
            assertTrue(context.unbound)
            assertEquals(1, admission.availablePermits())
        } finally {
            serviceThread.quitSafely()
        }
    }

    @Test
    fun genericIpcErrorThrowsIllegalStateException() = offMain {
        val generic = WhistleProtocol.errorBundle("Whistle worker failed", WhistleProtocol.ERROR_CODE_GENERIC)
        val error = assertThrows(IllegalStateException::class.java) { WhistleProtocol.throwError(generic) }
        assertEquals("Whistle worker failed", error.message)
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

    private class FakeBoundContext(private val serviceMessenger: Messenger) :
        ContextWrapper(ApplicationProvider.getApplicationContext()) {
        var unbound = false
        private var activeConnection: ServiceConnection? = null
        override fun getApplicationContext(): Context = this
        override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean {
            activeConnection = connection
            connection.onServiceConnected(ComponentName(packageName, WhistleService::class.java.name), serviceMessenger.binder)
            return true
        }
        override fun unbindService(connection: ServiceConnection) {
            unbound = true
            activeConnection?.onServiceDisconnected(ComponentName(packageName, WhistleService::class.java.name))
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
