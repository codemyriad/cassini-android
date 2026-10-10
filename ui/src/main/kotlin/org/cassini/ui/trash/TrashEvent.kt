package org.cassini.ui.trash

import androidx.compose.runtime.Immutable

/** What Trash asks for (features.md section 8). */
sealed interface TrashEvent {
    data object Back : TrashEvent

    /** The header's Empty. */
    data object Empty : TrashEvent

    @Immutable
    data class Restore(val id: String) : TrashEvent

    @Immutable
    data class DeleteNow(val id: String) : TrashEvent

    /** The confirm button of [TrashUiState.dialog]. */
    data object ConfirmDialog : TrashEvent

    data object DismissDialog : TrashEvent

    data object Undo : TrashEvent

    data object SnackbarTimeout : TrashEvent
}
