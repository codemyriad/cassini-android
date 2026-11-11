package io.codemyriad.cassini.data

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Close a provider's blocked pipe on cancellation; normal close/flush failures still reach the caller. */
suspend fun <T : Closeable, R> T.useCancellable(block: suspend (T) -> R): R = coroutineScope {
    val finished = AtomicBoolean(false)
    val closer = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            if (!finished.get()) {
                try {
                    this@useCancellable.close()
                } catch (_: IOException) { /* Cancellation already failed the operation. */ }
            }
        }
    }
    try {
        this@useCancellable.use { block(it) }
    } finally {
        finished.set(true)
        closer.cancel()
    }
}
