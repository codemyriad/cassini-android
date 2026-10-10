package org.cassini.android.notices

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A note that a screen other than the Library moved to trash: the Library shows its Undo snackbar. */
data class TrashedNote(val noteId: String, val title: String)

/**
 * The hand-over of "moved to trash" from the Note screen to the Library (architecture section 6: after Move to trash
 * the user lands on the Library with the Undo snackbar). The Note screen [offer]s the note and pops; the Library
 * collects [pending], shows the snackbar and [take]s it, so it shows once.
 */
class TrashUndo {
    private val state = MutableStateFlow<TrashedNote?>(null)

    val pending: StateFlow<TrashedNote?> = state.asStateFlow()

    fun offer(note: TrashedNote) {
        state.value = note
    }

    /** Clears [note] when it is still the pending one. */
    fun take(note: TrashedNote) {
        state.compareAndSet(note, null)
    }
}
