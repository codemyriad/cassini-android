package org.cassini.ui.licenses

import androidx.compose.runtime.Immutable

/** What Licenses asks for. */
sealed interface LicensesEvent {
    data object Back : LicensesEvent

    @Immutable
    data class Open(val entry: LicenseEntry) : LicensesEvent

    /** The text pane's back button. */
    data object CloseText : LicensesEvent
}
