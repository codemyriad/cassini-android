package org.cassini.core.export

import org.cassini.core.format.Word

/**
 * The input of every export writer (features.md section 9).
 *
 * [words] are the selected variant's words in canonical order; the writers never re-sort them. [labels] maps a speaker
 * id to its display label: a named label verbatim, a placeholder as the localized "Speaker N". A speaker without a
 * label is shown by its id.
 *
 * TXT, SRT and VTT exports are word-body derivatives without integrity data; only the sealed `.opus` is a portable
 * Cassini artifact (architecture section 12).
 */
data class ExportTranscript(
    val title: String,
    val durationMs: Long,
    val words: List<Word>,
    val labels: Map<String, String>,
) {
    /** The number of distinct speakers that say at least one word. */
    val speakerCount: Int = words.mapTo(HashSet()) { it.speaker }.size

    /** The display label of [speaker], or the id itself when [labels] has none. */
    fun label(speaker: String): String = labels[speaker] ?: speaker
}
