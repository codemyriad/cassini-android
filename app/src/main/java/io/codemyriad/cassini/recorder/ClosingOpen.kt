package io.codemyriad.cassini.recorder

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Opens a resource that must be closed on every path that does not hand it to the caller.
 *
 * [create] makes the resource, [connect] blocks until it is usable (on [io]; a blocking connect that ignores interrupts
 * is stopped by [close], which a cancelled caller runs at once; [close] may run twice), and [wrap] turns it into the result. If any step
 * fails, also with an unexpected exception, or the caller is cancelled (also just after [connect] returned, when
 * `withContext` drops its result), the resource is closed before the error goes on. A Bluetooth socket left to the
 * garbage collector is reported by CloseGuard and holds its RFCOMM channel until then.
 */
@Suppress("TooGenericExceptionCaught") // every failure, expected or not, must close the resource; it is rethrown
internal suspend fun <S, R> openClosing(
    io: CoroutineDispatcher,
    create: () -> S,
    connect: (S) -> Unit,
    close: (S) -> Unit,
    wrap: (S) -> R,
): R {
    val resource = withContext(io) { create() }
    try {
        withContext(io) {
            coroutineScope {
                val connecting = async { connect(resource) }
                try {
                    connecting.await()
                } catch (cancelled: CancellationException) {
                    // The blocked connect ends only when the resource closes.
                    close(resource)
                    throw cancelled
                }
            }
        }
        return wrap(resource)
    } catch (error: Throwable) {
        close(resource)
        // A cancelled caller gets its cancellation, not the IOException of the connect that the close ended.
        currentCoroutineContext().ensureActive()
        throw error
    }
}
