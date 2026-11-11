package io.codemyriad.cassini.flows

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityEvent
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.MainTestClock
import androidx.core.os.HandlerCompat
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Observes Compose changes, actual View traversal and native window events in the device flow tests. */
internal object UiEvents {
    // These Compose flows own this listener during a wait; they do not use UiDevice's accessibility listener.
    fun await(root: View, timeoutMs: Long, clock: MainTestClock? = null, condition: () -> Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val revisions = MutableStateFlow(0L)
        val changed = { revisions.update { it + 1 } }
        val layout = ViewTreeObserver.OnGlobalLayoutListener { changed() }
        val draw = ViewTreeObserver.OnDrawListener { changed() }
        val focus = ViewTreeObserver.OnWindowFocusChangeListener { changed() }
        val info = automation.serviceInfo
        val originalTypes = info.eventTypes
        val originalFlags = info.flags
        val snapshots = Snapshot.registerApplyObserver { _, _ -> changed() }
        var observer: ViewTreeObserver? = null
        try {
            info.eventTypes = info.eventTypes or WINDOW_EVENTS
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            automation.serviceInfo = info
            automation.setOnAccessibilityEventListener { event ->
                if (event.eventType and WINDOW_EVENTS != 0) changed()
            }
            onMain(timeoutMs) {
                observer = root.viewTreeObserver.also {
                    it.addOnGlobalLayoutListener(layout)
                    it.addOnDrawListener(draw)
                    it.addOnWindowFocusChangeListener(focus)
                }
            }
            val completed = awaitChanges(timeoutMs, clock, revisions, condition)
            if (completed != true) {
                throw ComposeTimeoutException("The UI condition did not become true within $timeoutMs ms")
            }
        } finally {
            snapshots.dispose()
            automation.setOnAccessibilityEventListener(null)
            info.eventTypes = originalTypes
            info.flags = originalFlags
            automation.serviceInfo = info
            onMain(timeoutMs) {
                observer?.takeIf { it.isAlive }?.let {
                    it.removeOnGlobalLayoutListener(layout)
                    it.removeOnDrawListener(draw)
                    it.removeOnWindowFocusChangeListener(focus)
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class)
    @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
    private fun awaitChanges(
        timeoutMs: Long,
        clock: MainTestClock?,
        revisions: MutableStateFlow<Long>,
        condition: () -> Boolean,
    ): Boolean? = runBlocking {
        val scheduled = clock?.let {
            launch(start = CoroutineStart.UNDISPATCHED) {
                while (true) {
                    it.scheduler.receiveDispatchEvent()
                    revisions.update { value -> value + 1 }
                }
            }
        }
        try {
            withTimeoutOrNull(timeoutMs) {
                while (true) {
                    ensureActive()
                    val before = revisions.value
                    if (clock?.autoAdvance == true) clock.advanceTimeByFrame()
                    if (condition()) return@withTimeoutOrNull true
                    if (clock == null || !clock.autoAdvance || clock.scheduler.isIdle()) {
                        revisions.first { it != before }
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                false
            }
        } finally {
            scheduled?.cancel()
        }
    }

    /** A new draw must occur before comparing layout positions; this does not claim pixel capture readiness. */
    fun draw(root: View, timeoutMs: Long) {
        val drawn = CountDownLatch(1)
        val callback = ViewTreeObserver.OnDrawListener { drawn.countDown() }
        var observer: ViewTreeObserver? = null
        try {
            onMain(timeoutMs) {
                observer = root.viewTreeObserver.also { it.addOnDrawListener(callback) }
                root.invalidate()
            }
            if (!drawn.await(timeoutMs, TimeUnit.MILLISECONDS)) throw AssertionError("The window did not draw")
        } finally {
            onMain(timeoutMs) {
                observer?.takeIf { it.isAlive }?.removeOnDrawListener(callback)
            }
        }
    }

    /** Async messages can cross traversal barriers while a regression deliberately prevents drawing. */
    fun <T> onMain(timeoutMs: Long, action: () -> T): T {
        val task = FutureTask { action() }
        check(HandlerCompat.createAsync(Looper.getMainLooper()).post(task)) { "The main looper rejected the UI action" }
        return try {
            task.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
        } finally {
            task.cancel(false)
        }
    }

    private const val WINDOW_EVENTS = AccessibilityEvent.TYPE_WINDOWS_CHANGED or
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
        AccessibilityEvent.TYPE_VIEW_SCROLLED or AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
        AccessibilityEvent.TYPE_VIEW_FOCUSED
}
