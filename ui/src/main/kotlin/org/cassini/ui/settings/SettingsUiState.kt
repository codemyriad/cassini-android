package org.cassini.ui.settings

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import org.cassini.ui.format.ModelPartUi

/**
 * Settings (board 08, features.md section 11; ui.md section 7). [voices] and [trash] are the counts of the Privacy
 * rows ("4 people", "2 notes"; 0 reads "None yet" and "Empty"). [version] is the `versionName` of the About card.
 * [notificationsOff] shows the notifications banner.
 */
@Immutable
data class SettingsUiState(
    val language: LanguageChoice,
    val models: ImmutableList<ModelRowUi>,
    val voices: Int,
    val trash: Int,
    val version: String,
    val notificationsOff: Boolean = false,
)

/**
 * The app language (coordination D50, D50.1, D53): [PHONE] follows the system, then the six app languages with their
 * BCP 47 [tag]. [OTHER] is a language that is not one of them, set for the app outside Settings (adb, another app):
 * no choice of the sheet is selected, and each one, Phone included, sets the app language again.
 */
enum class LanguageChoice(val tag: String?) {
    PHONE(null),
    ENGLISH("en"),
    ITALIAN("it"),
    FRENCH("fr"),
    SPANISH("es"),
    PORTUGUESE_BRAZIL("pt-BR"),
    PORTUGUESE_PORTUGAL("pt-PT"),
    OTHER(null),
    ;

    companion object {
        /** The choices of the App language sheet, in its order. */
        val offered: List<LanguageChoice> = entries.filter { it != OTHER }
    }
}

/** One On-device models row: the part, its size in bytes and its status text. */
@Immutable
data class ModelRowUi(val part: ModelPartUi, val bytes: Long, val status: ModelStatusUi)

/** The status of a model row (features.md section 11). */
sealed interface ModelStatusUi {
    /** "Ready" (teal). */
    data object Ready : ModelStatusUi

    /** "Downloading 46%" (blue). */
    @Immutable
    data class Downloading(val percent: Int) : ModelStatusUi

    /** "Checking files" (blue). */
    data object Checking : ModelStatusUi

    /** "Downloads with next transcription" (consented and missing; amber). */
    data object Pending : ModelStatusUi

    /** "Not downloaded" (no consent); the row opens Model download. */
    data object NotDownloaded : ModelStatusUi

    /** "Download failed — tries again next time" (red). */
    data object Failed : ModelStatusUi
}
