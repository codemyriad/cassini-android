package io.codemyriad.cassini.testing

import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.core.os.HandlerCompat
import kotlinx.coroutines.Delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
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

    /** Drains actual main work, then suspends for a post or Compose snapshot apply; [timeoutMs] only bounds a hung test. */
    fun await(timeoutMs: Long, condition: () -> Boolean) = runBlocking {
        val snapshots = Snapshot.registerApplyObserver { _, _ -> posted.update { it + 1 } }
        try {
            withTimeout(timeoutMs) {
                while (true) {
                    val revision = posted.value
                    shadowOf(Looper.getMainLooper()).idle()
                    if (condition()) return@withTimeout
                    // Posts and snapshot applies during the check are retained.
                    posted.first { it != revision }
                }
            }
        } finally {
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
