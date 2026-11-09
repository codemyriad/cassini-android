package io.codemyriad.cassini.core.library

import io.codemyriad.cassini.core.export.ReadingTranscript
import io.codemyriad.cassini.core.export.ReadingTurns
import io.codemyriad.cassini.core.format.Word

/** A Library excerpt with separate leading/trailing omission markers. Stored word text is never rewritten. */
data class Preview(val text: String, val leadingEllipsis: Boolean, val trailingEllipsis: Boolean) {
    val display: String get() = (if (leadingEllipsis) "…" else "") + text + (if (trailingEllipsis) "…" else "")
}

/** Library excerpts use the reader's order, paragraphs and inline interjection seams (D84). */
object PreviewText {
    /** Approximate excerpt budget in UTF-16 units; a single oversized word remains a whole atom. */
    const val LIMIT = 160
    const val MIN_BEFORE_TURN_END = 40

    fun of(
        words: List<Word>,
        positionMs: Long,
        labels: Map<String, String> = emptyMap(),
        endsBoundedByAudio: Boolean = false,
    ): Preview? {
        if (words.isEmpty()) return null
        val reading = ReadingTurns.of(words, endsBoundedByAudio)
        val atoms = atoms(reading, labels)
        val first = startAt(reading, atoms, positionMs)
        val text = StringBuilder()
        var next = first
        while (next < atoms.size) {
            val atom = atoms[next]
            val prefix = if (next == first && atom.chipLabel != null) "(${atom.chipLabel}: " else atom.prefix
            val part = prefix + atom.word.text + atom.suffix
            val budgetReached = next > first && text.length + 1 + part.length > LIMIT
            if (budgetReached || stopsBefore(atoms, first, next, text.length)) break
            if (next > first) text.append(' ')
            text.append(part)
            next++
        }
        return Preview(text.toString(), leadingEllipsis = first > 0, trailingEllipsis = next < atoms.size)
    }

    private fun atoms(reading: ReadingTranscript, labels: Map<String, String>): List<Atom> = reading.rows.flatMap { row ->
        row.members.flatMap { member ->
            val label = member.interjectionSpeaker?.let { labels[it] ?: it }
            member.wordIndices.mapIndexed { index, canonical ->
                Atom(
                    canonical,
                    row.key,
                    reading.words[canonical],
                    if (label != null && index == 0) "($label: " else "",
                    if (label != null && index == member.wordIndices.lastIndex) ")" else "",
                    label,
                )
            }
        }
    }

    private fun startAt(reading: ReadingTranscript, atoms: List<Atom>, positionMs: Long): Int {
        val playing = reading.words.indices.sortedBy { reading.words[it].startMs }
            .firstOrNull { reading.words[it].endMs > positionMs }
        return playing?.let { canonical -> atoms.indexOfFirst { it.canonical == canonical } } ?: 0
    }

    private fun stopsBefore(atoms: List<Atom>, first: Int, next: Int, length: Int): Boolean {
        if (first == 0 || next == first || length < MIN_BEFORE_TURN_END) return false
        return atoms[next].row != atoms[next - 1].row
    }

    private data class Atom(
        val canonical: Int,
        val row: Int,
        val word: Word,
        val prefix: String,
        val suffix: String,
        val chipLabel: String?,
    )
}
