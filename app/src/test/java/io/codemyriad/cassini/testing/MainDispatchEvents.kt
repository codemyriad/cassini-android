package io.codemyriad.cassini.testing

import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.core.os.HandlerCompat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.rules.ExternalResource
import org.robolectric.Shadows.shadowOf
import kotlin.coroutines.CoroutineContext

/** Observes coroutine posts without replacing Android's main queue or its immediate-dispatch semantics. */
@OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class)
class MainDispatchEvents : ExternalResource() {
    private val posted = MutableStateFlow(0L)

    override fun before() {
        Dispatchers.setMain(ObservedMain(HandlerCompat.createAsync(Looper.getMainLooper()).asCoroutineDispatcher()))
    }

    override fun after() = Dispatchers.resetMain()

    /** Drains main work and waits for the next coroutine post or Compose snapshot application. */
    fun await(timeoutMs: Long, condition: () -> Boolean) = await(timeoutMs, scheduler = null, condition = condition)

    /** The UI caller drives virtual work; beforeAwait acknowledges the decision to await a publication. */
    // The pinned coroutine-test scheduler exposes dispatch events internally.
    @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
    fun await(
        timeoutMs: Long,
        scheduler: TestCoroutineScheduler?,
        beforeAwait: () -> Unit = {},
        condition: () -> Boolean,
    ) = runBlocking {
        val snapshots = Snapshot.registerApplyObserver { _, _ -> posted.update { it + 1 } }
        val scheduled = scheduler?.let {
            launch(start = CoroutineStart.UNDISPATCHED) {
                while (true) {
                    scheduler.receiveDispatchEvent()
                    posted.update { it + 1 }
                }
            }
        }
        try {
            withTimeout(timeoutMs) {
                while (true) {
                    yield()
                    val revision = posted.value
                    val looper = shadowOf(Looper.getMainLooper())
                    looper.idle()
                    if (condition()) return@withTimeout
                    when {
                        scheduler != null && !scheduler.isIdle() -> Unit
                        scheduler != null && !looper.nextScheduledTaskTime.isZero -> looper.runToNextTask()
                        else -> {
                            beforeAwait()
                            posted.first { it != revision }
                        }
                    }
                }
            }
        } finally {
            scheduled?.cancel()
            snapshots.dispose()
        }
    }

    private inner class ObservedMain(private val delegate: MainCoroutineDispatcher) :
        MainCoroutineDispatcher(), Delay by (delegate as Delay) {
        override val immediate: MainCoroutineDispatcher =
            if (delegate.immediate === delegate) this else ObservedMain(delegate.immediate)

        override fun isDispatchNeeded(context: CoroutineContext): Boolean = delegate.isDispatchNeeded(context)

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            delegate.dispatch(context, block)
            posted.update { it + 1 }
        }
    }
}
