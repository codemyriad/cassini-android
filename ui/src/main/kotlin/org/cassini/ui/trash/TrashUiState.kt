package org.cassini.ui.trash

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import org.cassini.ui.components.SnackbarSpec
import org.cassini.ui.format.DayLabel

/**
 * Trash (board 09, features.md section 8; ui.md section 7). [items] are sorted oldest deletion first (the soonest to
 * expire on top); an empty list shows "Trash is empty" and hides Empty. [backTo] picks the back button's label.
 * [snackbar] is a fixture-only state for board 09 (ui.md section 11, item 11): the app shows that snackbar on the
 * Library.
 */
@Immutable
data class TrashUiState(
    val items: ImmutableList<TrashItemUi>,
    val backTo: TrashBackTo = TrashBackTo.LIBRARY,
    val snackbar: SnackbarSpec? = null,
    val dialog: TrashDialog? = null,
)

/** One trashed note: "Deleted 2 Oct · 23 days left" from [deletedOn] and [daysLeft]. */
@Immutable
data class TrashItemUi(
    val id: String,
    val title: String,
    val durationMs: Long,
    val deletedOn: DayLabel,
    val daysLeft: Int,
)

/** Where Back returns: "Back to library" or "Back to settings". */
enum class TrashBackTo { LIBRARY, SETTINGS }

/** A Trash confirmation. */
sealed interface TrashDialog {
    /** "Delete “<title>” for good?". */
    @Immutable
    data class Delete(val id: String, val title: String) : TrashDialog

    /** "Delete N notes for good?". */
    @Immutable
    data class EmptyAll(val count: Int) : TrashDialog
}
