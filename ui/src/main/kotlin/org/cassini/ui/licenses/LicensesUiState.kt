package org.cassini.ui.licenses

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList

/**
 * Licenses, "Source code and notices" (no board; features.md section 11; ui.md sections 7 and 8). [entries] are the
 * rows in order; [open] is the text pane of an opened row. [backTo] picks the back button's label: Licenses opens
 * from Settings and from Model download ("Where the models come from · Privacy"). It is an addition to ui.md
 * section 7, as `TrashUiState.backTo`.
 */
@Immutable
data class LicensesUiState(
    val entries: ImmutableList<LicenseEntry>,
    val open: LicenseText? = null,
    val backTo: LicensesBackTo = LicensesBackTo.SETTINGS,
)

/** Where the Licenses back button goes: "Back to settings", or "Back" to Model download. */
enum class LicensesBackTo { SETTINGS, MODEL_DOWNLOAD }

/** A row of Licenses. [SOURCE_CODE] opens the browser; every other row opens its text. */
enum class LicenseEntry {
    CASSINI,
    THIRD_PARTY,
    NATIVE,
    INSTRUMENT_SANS,
    PLEX_MONO,
    MODELS,
    MODEL_SOURCES,
    PRIVACY,
    SOURCE_CODE,
}

/** The text of an opened [entry], read from the app's assets. */
@Immutable
data class LicenseText(val entry: LicenseEntry, val text: String)
