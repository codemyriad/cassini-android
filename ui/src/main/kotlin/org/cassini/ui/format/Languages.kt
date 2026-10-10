package org.cassini.ui.format

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import java.util.Locale

/** Transcript languages (Versions sheet, Document info): a BCP 47 tag shown in the app language. */
object Languages {
    /** "Italian" ("Italiano" in Italian) for "it"; the tag itself when the platform has no name for it. */
    fun name(tag: String, format: FormatSettings): String {
        val name = Locale.forLanguageTag(tag).getDisplayName(format.locale)
        return if (name.isBlank()) tag else name.replaceFirstChar { it.titlecase(format.locale) }
    }

    @Composable
    @ReadOnlyComposable
    fun name(tag: String): String = name(tag, format())
}
