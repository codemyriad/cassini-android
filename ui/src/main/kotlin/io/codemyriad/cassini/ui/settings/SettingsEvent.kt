package io.codemyriad.cassini.ui.settings

import androidx.compose.runtime.Immutable
import io.codemyriad.cassini.ui.format.ModelPartUi

/** What Settings asks for (features.md section 11). */
sealed interface SettingsEvent {
    data object DownloadParakeet : SettingsEvent

    data object DismissParakeet : SettingsEvent

    data object DismissParakeetDefault : SettingsEvent

    data class Transfer(val enabled: Boolean) : SettingsEvent

    /** "Encrypted cloud backup" (D98). */
    data class Cloud(val enabled: Boolean) : SettingsEvent

    /** "Include saved voices in backups" (D98). */
    data class IncludeVoices(val enabled: Boolean) : SettingsEvent

    data object Back : SettingsEvent

    @Immutable
    data class Language(val language: LanguageChoice) : SettingsEvent

    data class ChooseSpeech(val model: SpeechModelUi) : SettingsEvent

    /** A missing or failed optional model row opens Model download. */
    @Immutable
    data class OpenModel(val part: ModelPartUi) : SettingsEvent

    data class SpeakerChoice(val part: ModelPartUi, val enabled: Boolean) : SettingsEvent

    /** "Remember people I name" (D103): the default of "Remember this voice" in Name a speaker. */
    data class RememberVoices(val enabled: Boolean) : SettingsEvent

    data object OpenPrivacy : SettingsEvent

    data object OpenSources : SettingsEvent

    data object OpenHelp : SettingsEvent

    data object OpenSourceCode : SettingsEvent

    data object OpenVoices : SettingsEvent

    data object OpenTrash : SettingsEvent

    /** "Source code and notices". */
    data object OpenLicenses : SettingsEvent

    /** The notifications banner's Turn on. */
    data object TurnOnNotifications : SettingsEvent
}
