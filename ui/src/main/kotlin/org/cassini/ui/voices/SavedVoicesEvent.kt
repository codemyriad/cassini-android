package org.cassini.ui.voices

import androidx.compose.runtime.Immutable

/** What Saved voices asks for (features.md section 10). */
sealed interface SavedVoicesEvent {
    data object Back : SavedVoicesEvent

    /** "Options for <name>". */
    @Immutable
    data class OpenMenu(val id: String) : SavedVoicesEvent

    data object DismissMenu : SavedVoicesEvent

    @Immutable
    data class Rename(val id: String) : SavedVoicesEvent

    @Immutable
    data class Forget(val id: String) : SavedVoicesEvent

    data object ForgetEveryone : SavedVoicesEvent

    @Immutable
    data class RenameConfirmed(val id: String, val name: String) : SavedVoicesEvent

    /** The confirm button of a Forget or Forget everyone dialog. */
    data object ConfirmDialog : SavedVoicesEvent

    data object DismissDialog : SavedVoicesEvent
}
