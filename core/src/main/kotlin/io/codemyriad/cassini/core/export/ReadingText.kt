package io.codemyriad.cassini.core.export

import io.codemyriad.cassini.core.format.Word

/** Shared copy/export/excerpt text: stored atoms with explicit inline-speaker punctuation. */
object ReadingText {
    fun body(words: List<Word>, label: (String) -> String = { it }, endsBoundedByAudio: Boolean = false): String {
        val reading = ReadingTurns.of(words, endsBoundedByAudio)
        return reading.rows.joinToString(" ") { paragraph(it, reading.words, label) }
    }

    fun paragraph(row: ReadingRow, words: List<Word>, label: (String) -> String = { it }): String =
        row.members.joinToString(" ") { member ->
            val text = member.wordIndices.joinToString(" ") { words[it].text }
            member.interjectionSpeaker?.let { "(${label(it)}: $text)" } ?: text
        }

    /** Viewer formatSpeakerList: a final "and", with no Oxford comma. */
    fun speakerList(labels: List<String>): String = when (labels.size) {
        0 -> ""
        1 -> labels.single()
        else -> labels.dropLast(1).joinToString(", ") + " and " + labels.last()
    }

    fun over(row: ReadingRow, label: (String) -> String = { it }): String =
        row.over.map(label).distinct().takeIf { it.isNotEmpty() }?.let { "(over ${speakerList(it)})" }.orEmpty()
}
