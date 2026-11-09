package io.codemyriad.cassini.core.export

import io.codemyriad.cassini.core.format.Word

/** A whole reading run, including a run rendered inline as an interjection. */
data class Turn(val speaker: String, val startMs: Long, val endMs: Long, val words: List<Word>) {
    /** The words joined with one space. */
    val text: String get() = words.joinToString(" ") { it.text }
}

/** Compatibility projection of the shared reading model's whole runs (not paragraphs). */
object TranscriptTurns {
    /** Display runs in reading order; canonical words are not mutated. Chip runs remain countable. */
    fun of(words: List<Word>, endsBoundedByAudio: Boolean = false): List<Turn> {
        val reading = ReadingTurns.of(words, endsBoundedByAudio)
        return reading.runs.map { run ->
            Turn(run.speaker, run.startMs, run.endMs, run.wordIndices.map { reading.words[it] })
        }
    }
}
