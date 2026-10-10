package org.cassini.ui.settings

import androidx.compose.runtime.Immutable
import org.cassini.ui.format.ModelPartUi

/** What Settings asks for (features.md section 11). */
sealed interface SettingsEvent {
    data object Back : SettingsEvent

    @Immutable
    data class Language(val language: AppLanguage) : SettingsEvent

    /** A model row; only a Not downloaded row opens Model download. */
    @Immutable
    data class OpenModel(val part: ModelPartUi) : SettingsEvent

    data object OpenVoices : SettingsEvent

    data object OpenTrash : SettingsEvent

    /** "Source code and notices". */
    data object OpenLicenses : SettingsEvent

    /** The notifications banner's Turn on. */
    data object TurnOnNotifications : SettingsEvent
}
