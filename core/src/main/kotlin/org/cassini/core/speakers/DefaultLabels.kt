package org.cassini.core.speakers

/**
 * Whether a speaker label is still a placeholder rather than a name. Sealed documents and `names.json` store only the
 * label text, so this is a string scan over every placeholder the app has ever written: the `ui_speaker_label`
 * string of :ui ("Speaker %1$d"; Italian "Voce", French "Intervenant", Spanish "Hablante", Brazilian Portuguese
 * "Locutor", European Portuguese "Orador", as the D50 glossary writes them), which the job writes and the
 * transcript shows.
 * DefaultLabelsTest checks the prefixes against the string resources.
 */
object DefaultLabels {
    /** `ui_speaker_label` up to its number, in every shipped language. */
    val PREFIXES = listOf("Speaker ", "Voce ", "Intervenant ", "Hablante ", "Locutor ", "Orador ")

    /** True for the speaker id itself, a blank label, or a placeholder prefix followed by a number. */
    fun isDefault(id: String, label: String): Boolean = label == id || label.isBlank() ||
        PREFIXES.any { label.startsWith(it) && label.removePrefix(it).toIntOrNull() != null }
}
