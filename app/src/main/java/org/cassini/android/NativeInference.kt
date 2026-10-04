package org.cassini.android

/** Native work cannot always stop mid-call. Keep its models out of the next job's memory budget. */
internal object NativeInference {
    val lease = java.util.concurrent.Semaphore(1, true)
    fun acquire() {
        try { lease.acquire() }
        catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw UserFacingException(Failure.CANCELLED)
        }
    }
}
