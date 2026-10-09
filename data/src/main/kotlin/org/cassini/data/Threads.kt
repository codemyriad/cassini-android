package org.cassini.data

import android.os.Looper

/** Thread checks of the storage layer (docs/architecture.md, section 7). */
object Threads {
    /** Whether the caller runs on the main thread. */
    fun isMain(): Boolean = Looper.getMainLooper().isCurrentThread

    /**
     * Fails when called on the main thread. Every `…Blocking` entry point of a repository calls it first, because
     * file I/O and Room never run on the main thread.
     */
    fun assertOffMain(what: String) {
        check(!isMain()) { "$what must not run on the main thread" }
    }
}
