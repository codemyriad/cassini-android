package org.cassini.core.speakers

import org.cassini.core.format.Word

data class SpeakerTurn(val startMs: Long, val endMs: Long, val speaker: Int)

/** Sortformer settings and the word-to-speaker assignment. The native diarizer lives in the app. */
object Diarization {
    /** Speaker activity above this probability counts as speech. The model decides the number of speakers (up to 8). */
    const val THRESHOLD = 0.5f
    const val MIN_DURATION_ON = 0.3f
    const val MIN_DURATION_OFF = 0.5f

    /** Keep word order, text and timings. Number anonymous speakers in order of first spoken word. */
    fun assign(words: List<Word>, turns: List<SpeakerTurn>, prefix: String = "spk_", stableIds: Boolean = false): List<Word> {
        // Union duplicate/overlapping intervals for a speaker before counting coverage. Sort to
        // make ties independent of native result order; invalid turns cannot win a nearest match.
        val valid = turns.filter { it.startMs >= 0 && it.endMs > it.startMs && it.speaker >= 0 }
        val union = valid.groupBy { it.speaker }.flatMap { (speaker, intervals) ->
            val merged = mutableListOf<SpeakerTurn>()
            for (turn in intervals.sortedWith(compareBy({ it.startMs }, { it.endMs }))) {
                val previous = merged.lastOrNull()
                if (previous != null && turn.startMs <= previous.endMs) {
                    merged[merged.lastIndex] = previous.copy(endMs = maxOf(previous.endMs, turn.endMs))
                } else merged.add(turn.copy(speaker = speaker))
            }
            merged
        }.sortedWith(compareBy({ it.startMs }, { it.endMs }, { it.speaker }))
        if (union.isEmpty()) return words
        val ids = LinkedHashMap<Int, String>()
        return words.map { word ->
            val overlaps = LinkedHashMap<Int, Long>()
            for (turn in union) {
                val overlap = minOf(word.endMs, turn.endMs) - maxOf(word.startMs, turn.startMs)
                if (overlap > 0) overlaps[turn.speaker] = (overlaps[turn.speaker] ?: 0) + overlap
            }
            val speaker = overlaps.maxByOrNull { it.value }?.key ?: union.minBy { gap(word, it) }.speaker
            word.copy(speaker = if (stableIds) "$prefix${speaker + 1}" else ids.getOrPut(speaker) { "$prefix${ids.size + 1}" })
        }
    }

    private fun gap(word: Word, turn: SpeakerTurn) = maxOf(turn.startMs - word.endMs, word.startMs - turn.endMs, 0L)
}
