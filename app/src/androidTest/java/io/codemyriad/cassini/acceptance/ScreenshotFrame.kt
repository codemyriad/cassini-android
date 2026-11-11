package io.codemyriad.cassini.acceptance

import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Waits for each screenshot window to submit an actual frame through its hardware renderer. */
internal fun awaitDrawnFrames(roots: List<View>, name: String, timeoutMs: Long) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        throw AssertionError("Acceptance screenshots require API 29 or later")
    }
    assertTrue("No screenshot windows for $name", roots.isNotEmpty())
    val committed = CountDownLatch(roots.size)
    val callbacks = mutableListOf<Pair<ViewTreeObserver, Runnable>>()
    try {
        onMain {
            roots.forEach { root ->
                assertTrue(
                    "Acceptance screenshots require hardware-accelerated windows ($name)",
                    root.isHardwareAccelerated,
                )
            }
            roots.forEach { root ->
                val observer = root.viewTreeObserver
                val callback = Runnable { committed.countDown() }
                callbacks += observer to callback
                observer.registerFrameCommitCallback(callback)
                root.invalidate()
            }
        }
        if (!committed.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw AssertionError("A window of $name did not commit a frame (waited ${timeoutMs / 1_000} s)")
        }
    } finally {
        onMain {
            callbacks.forEach { (observer, callback) ->
                if (observer.isAlive) observer.unregisterFrameCommitCallback(callback)
            }
        }
    }
}

/** Propagates UI-thread assertions to the instrumentation caller. */
private fun onMain(action: () -> Unit) {
    val task = FutureTask { action() }
    InstrumentationRegistry.getInstrumentation().runOnMainSync(task)
    try {
        task.get()
    } catch (failure: ExecutionException) {
        throw failure.cause ?: failure
    }
}
