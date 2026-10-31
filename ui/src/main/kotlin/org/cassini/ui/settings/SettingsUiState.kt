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
 * The app language: the segments Phone (follow the system), English and Italiano, and [OTHER], a language set for the
 * app outside it (Android's per-app language setting, with the languages of `locales_config`) that has no segment.
 * With [OTHER] no segment is selected, and each segment, Phone included, sets the app language again.
 */
enum class LanguageChoice { PHONE, ENGLISH, ITALIAN, OTHER }

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
