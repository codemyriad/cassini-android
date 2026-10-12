package org.cassini.core.speakers

import org.cassini.core.export.TranscriptTurns
import org.cassini.core.export.Turn
import org.cassini.core.format.SpeakerMerges
import org.cassini.core.format.Transcript
import org.cassini.core.format.Word

/** The "Play a sample" range of a speaker, in milliseconds of the note's audio. */
data class SampleWindow(val startMs: Long, val endMs: Long)

/**
 * What a transcript says about one speaker. [ordinal] is the order of first appearance in the words (from 0),
 * [speakingMs] the summed length of the speaker's turns, [firstMs] the start of the speaker's first turn and [sample]
 * the "Play a sample" window, or null when no turn lasts [SpeakerStats.SAMPLE_MIN_MS].
 */
data class SpeakerStat(
    val speakerId: String,
    val ordinal: Int,
    val speakingMs: Long,
    val turnCount: Int,
    val wordCount: Int,
    val firstMs: Long,
    val sample: SampleWindow?,
)

/** Per-speaker numbers for the `speakers` table and the Name a speaker sheet, from the turns of a transcript. */
object SpeakerStats {
    /** A sample plays at most this much of its turn. */
    const val SAMPLE_MAX_MS = 10_000L

    /** A turn shorter than this is no sample. */
    const val SAMPLE_MIN_MS = 1_000L

    /**
     * The stats of every speaker in [words] (canonical order), keyed by speaker id in [SpeakerStat.ordinal] order.
     * Turns come from [TranscriptTurns.of]. The sample is the longest turn (ties: the earliest start, then the first
     * in canonical order), cut to [SAMPLE_MAX_MS].
     */
    fun of(words: List<Word>): Map<String, SpeakerStat> {
        val turns = TranscriptTurns.of(words).groupBy { it.speaker }
        val wordCounts = words.groupingBy { it.speaker }.eachCount()
        val result = LinkedHashMap<String, SpeakerStat>()
        for ((ordinal, id) in words.map { it.speaker }.distinct().withIndex()) {
            val own = turns.getValue(id)
            val longest = own.withIndex().minWith(
                compareByDescending<IndexedValue<Turn>> { length(it.value) }
                    .thenBy { it.value.startMs }
                    .thenBy { it.index },
            ).value
            val sample = if (length(longest) < SAMPLE_MIN_MS) {
                null
            } else {
                SampleWindow(longest.startMs, minOf(longest.endMs, longest.startMs + SAMPLE_MAX_MS))
            }
            result[id] = SpeakerStat(
                speakerId = id,
                ordinal = ordinal,
                speakingMs = own.sumOf { length(it) },
                turnCount = own.size,
                wordCount = wordCounts.getValue(id),
                firstMs = own.first().startMs,
                sample = sample,
            )
        }
        return result
    }

    /** The stats of a draft: [merges] (`names.json`) applied with [SpeakerMerges.apply] first. */
    fun of(transcript: Transcript, merges: Map<String, String>): Map<String, SpeakerStat> =
        of(SpeakerMerges.apply(transcript, merges).words)

    private fun length(turn: Turn) = (turn.endMs - turn.startMs).coerceAtLeast(0)
}
