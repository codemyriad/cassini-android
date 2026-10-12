package org.cassini.android.adapters

import org.cassini.core.checkpoint.CheckpointNames
import java.util.concurrent.ConcurrentHashMap

/**
 * Whether the draft names of a note accept changes from the UI (architecture 3.6 and 5.4). The job closes naming at
 * the attribution step (step 6, `JobStorage.closeNames`) and opens it again at the start of every run and when a run
 * that closed it ends without adopt (`JobStorage.openNames`).
 *
 * Every UI change of draft names goes through [guard]: the reducer it returns leaves the names unchanged while naming
 * is closed. The job's own changes (`JobStorage.mutateNames`) do not pass the gate. A UI change whose reducer already
 * ran when the job closes naming can still land in the draft; the job does not see it in its final value, and it stays
 * in `names.json` only when the run pauses afterwards.
 */
class NamingGate {
    private val closed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Whether the UI may change the draft names of [noteId]. */
    fun isOpen(noteId: String): Boolean = noteId !in closed

    fun open(noteId: String) {
        closed.remove(noteId)
    }

    fun close(noteId: String) {
        closed.add(noteId)
    }

    /** [reducer], or no change while naming of [noteId] is closed. The result is pure, as `NamesStore` requires. */
    fun guard(noteId: String, reducer: (CheckpointNames) -> CheckpointNames): (CheckpointNames) -> CheckpointNames =
        { names -> if (isOpen(noteId)) reducer(names) else names }
}
