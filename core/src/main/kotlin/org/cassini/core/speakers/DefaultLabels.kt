package org.cassini.core.speakers

/**
 * Whether a speaker label is still a placeholder rather than a name. Sealed documents and `names.json` store only the
 * label text, so this is a string scan over every placeholder the app has ever written: the `speaker_label` string
 * ("Speaker %1$d", Italian "Voce %1$d") in every shipped version. DefaultLabelsTest checks the prefixes against the
 * string resources.
 */
object DefaultLabels {
    /** `speaker_label` up to its number, in every shipped language. */
    val PREFIXES = listOf("Speaker ", "Voce ")

    /** True for the speaker id itself, a blank label, or a placeholder prefix followed by a number. */
    fun isDefault(id: String, label: String): Boolean = label == id || label.isBlank() ||
        PREFIXES.any { label.startsWith(it) && label.removePrefix(it).toIntOrNull() != null }
}
