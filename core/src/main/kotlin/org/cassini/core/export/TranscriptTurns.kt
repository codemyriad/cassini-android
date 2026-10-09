package org.cassini.core.export

import org.cassini.core.format.Word

/** A maximal run of consecutive words (canonical order) with the same speaker. */
data class Turn(val speaker: String, val startMs: Long, val endMs: Long, val words: List<Word>) {
    /** The words joined with one space. */
    val text: String get() = words.joinToString(" ") { it.text }
}

/** Splits a transcript into turns. Shared by the exports, the UI and `SpeakerStats`. */
object TranscriptTurns {
    /**
     * The turns of [words], in canonical order. A turn starts at its first word's start and ends at its last word's
     * end, as features.md section 9 says, even when an earlier word of the turn ends later.
     */
    fun of(words: List<Word>): List<Turn> {
        val turns = ArrayList<Turn>()
        var from = 0
        while (from < words.size) {
            var to = from + 1
            while (to < words.size && words[to].speaker == words[from].speaker) to++
            val run = words.subList(from, to).toList()
            turns += Turn(run.first().speaker, run.first().startMs, run.last().endMs, run)
            from = to
        }
        return turns
    }
}
