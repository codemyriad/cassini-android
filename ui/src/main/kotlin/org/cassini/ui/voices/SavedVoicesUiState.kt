package org.cassini.ui.voices

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList

/**
 * Saved voices (board 07, features.md section 10; ui.md section 7). [people] are sorted by notes, then confirmed
 * seconds, then name; an empty list shows "No saved voices yet". [readOnly] shows the VOICES_UNREADABLE banner and
 * disables the edits. [menuFor] is the id of the person whose options menu is open.
 */
@Immutable
data class SavedVoicesUiState(
    val people: ImmutableList<PersonUi>,
    val readOnly: Boolean = false,
    val menuFor: String? = null,
    val dialog: SavedVoicesDialog? = null,
)

/**
 * One saved voice: "14 notes · 22 min confirmed speech" from [notes] and [confirmedSeconds]; [stale] adds "Name them
 * again in a note to keep recognising them". The avatar tint follows the list position.
 */
@Immutable
data class PersonUi(
    val id: String,
    val name: String,
    val notes: Int,
    val confirmedSeconds: Long,
    val stale: Boolean = false,
)

/** A Saved voices dialog. */
sealed interface SavedVoicesDialog {
    /** Rename (max 80); [name] is the current name. */
    @Immutable
    data class Rename(val id: String, val name: String) : SavedVoicesDialog

    /** "Forget <name>?". */
    @Immutable
    data class Forget(val id: String, val name: String) : SavedVoicesDialog

    /** "Forget everyone?" with the [count] of saved voices. */
    @Immutable
    data class ForgetEveryone(val count: Int) : SavedVoicesDialog
}
