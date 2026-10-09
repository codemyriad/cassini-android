package org.cassini.engine.job

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.cassini.core.Failure
import org.cassini.core.UserFacingException

/**
 * Native work cannot always stop mid-call. Whoever loads native models (recognizer, diarizer, voiceprint extractor)
 * holds this budget until it has released them, so a cancelled job's models leave memory before the next job loads
 * its own. One holder at a time, served in the order they asked. It replaces 0.1.0's `NativeInference` semaphore.
 *
 * The wrappers in this module never take it themselves, except the one-call helpers that load and release a model
 * inside a single call ([org.cassini.engine.asr.Parakeet.transcribe],
 * [org.cassini.engine.speakers.SortformerStream.turns]).
 */
object NativeBudget {
    private val mutex = Mutex()

    /** Whether someone holds the budget now. */
    val isHeld: Boolean get() = mutex.isLocked

    /** Runs [block] while holding the budget; waiting for it can be cancelled. */
    suspend fun <T> withBudget(block: suspend () -> T): T = mutex.withLock { block() }

    /**
     * Blocks the calling thread until the budget is free and takes it, for code that runs on plain threads. An
     * interrupt while waiting ends in [Failure.CANCELLED] and keeps the thread's interrupt flag. Pair with [release].
     */
    fun acquire() {
        if (Thread.currentThread().isInterrupted) throw UserFacingException(Failure.CANCELLED)
        if (mutex.tryLock()) return
        try {
            runBlocking { mutex.lock() }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw UserFacingException(Failure.CANCELLED)
        }
    }

    /**
     * Takes the budget if it becomes free within [timeoutMs]; blocks the calling thread meanwhile. An interrupt ends
     * in [Failure.CANCELLED] and keeps the thread's interrupt flag, as in [acquire].
     */
    fun tryAcquire(timeoutMs: Long): Boolean {
        if (Thread.currentThread().isInterrupted) throw UserFacingException(Failure.CANCELLED)
        if (mutex.tryLock()) return true
        return try {
            runBlocking { withTimeoutOrNull(timeoutMs) { mutex.lock() } != null }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw UserFacingException(Failure.CANCELLED)
        }
    }

    /** Gives back the budget taken by [acquire] or [tryAcquire]. */
    fun release() = mutex.unlock()

    /** Runs [block] on the calling thread while holding the budget ([acquire] then [release]). */
    inline fun <T> holding(block: () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }
}
