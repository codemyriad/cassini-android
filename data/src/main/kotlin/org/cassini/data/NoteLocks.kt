package org.cassini.data

import androidx.annotation.WorkerThread
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The write locks of the note directories (docs/architecture.md, sections 4 and 7). Every write under
 * `notes/<id>/` (document, checkpoint, names, audio, `work/`) holds the note's lock.
 *
 * The locks are plain [ReentrantLock]s, not coroutine mutexes, because the inference thread takes them too and must
 * never suspend. A suspend caller takes the lock inside `withContext(io)` and runs a block that cannot suspend
 * ([withNoteLock]), so no lock is ever held across a suspension point.
 *
 * Locks are striped: note ids hash to one of [stripes] locks, so memory stays fixed however many notes exist. Two
 * notes can share a lock; that only serialises them more. Code must never wait for a second note's lock while it
 * holds one (two stripes taken in different orders could deadlock); reentrancy makes taking the same stripe twice safe.
 */
class NoteLocks(stripes: Int = DEFAULT_STRIPES) {
    private val locks = Array(stripes.coerceAtLeast(1)) { ReentrantLock() }

    /** The lock of [noteId]. The same id always gives the same lock. */
    fun lockFor(noteId: String): ReentrantLock = locks[Math.floorMod(noteId.hashCode(), locks.size)]

    /**
     * Runs [block] on [io] while holding the lock of [noteId]. [block] is not a suspend function, so it cannot
     * suspend while the lock is held; it runs blocking file work on the IO thread.
     */
    suspend fun <T> withNoteLock(noteId: String, io: CoroutineDispatcher, block: () -> T): T =
        withContext(io) { lockFor(noteId).withLock(block) }

    /** Runs [block] while holding the lock of [noteId], on the calling worker thread (the inference thread). */
    @WorkerThread
    fun <T> withNoteLockBlocking(noteId: String, block: () -> T): T {
        Threads.assertOffMain("withNoteLockBlocking")
        return lockFor(noteId).withLock(block)
    }

    companion object {
        const val DEFAULT_STRIPES = 64
    }
}
