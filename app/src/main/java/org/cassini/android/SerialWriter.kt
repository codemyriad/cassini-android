package org.cassini.android

import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs catalogue and session writes in order on one process-wide thread, so a screen never rewrites the
 * whole library (every note and transcript) on the main thread. It outlives screens: a write queued as a
 * screen closes still lands. Readers that must see earlier writes call [flush] first.
 */
class SerialWriter(name: String) {
    @Volatile private var owner: Thread? = null
    private val thread = Executors.newSingleThreadExecutor { Thread(it, name).apply { isDaemon = true; owner = this } }

    /** Queues [write]; a failure goes to [onError] on the writer thread, and later writes still run. */
    fun submit(onError: (Exception) -> Unit = { Log.e("Cassini", "Background write failed", it) }, write: () -> Unit) {
        thread.execute {
            try { write() } catch (error: Exception) { onError(error) }
        }
    }

    /** Waits up to [timeoutMs] for every write queued before this call; true when they all finished. */
    fun flush(timeoutMs: Long = 2_000): Boolean {
        // A write that reads sees every write before it already.
        if (Thread.currentThread() === owner) return true
        val done = CountDownLatch(1)
        thread.execute { done.countDown() }
        return done.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    companion object {
        /** The one writer of library.json and session.json from screens. */
        val library = SerialWriter("cassini-library-writer")
    }
}
