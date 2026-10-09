package org.cassini.core.export

import org.cassini.core.format.Word
import kotlin.math.abs

/**
 * One subtitle cue. [text] is the cue's words joined with one space, in canonical order. [prefix] is the SRT label
 * prefix (`"<label>: "`) or empty. [lines] is `prefix + text` in one line, or two lines when it is over
 * [SubtitleCues.LINE_LENGTH] characters.
 */
data class Cue(
    val speaker: String,
    val label: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val prefix: String,
    val lines: List<String>,
)

/** Splits a transcript into subtitle cues for [SrtWriter] and [VttWriter] (features.md section 9). */
object SubtitleCues {
    /** A silence of at least this long between two words starts a new cue. */
    const val PAUSE_MS = 1500L

    /** A cue never runs longer than this from its start to the end of its last word. */
    const val MAX_SPAN_MS = 6000L

    /** The longest cue text in characters (UTF-16 units), with the SRT label prefix of a speaker's first cue. */
    const val MAX_TEXT = 84

    /** A sentence end starts a new cue once the cue lasts at least this long. */
    const val SENTENCE_MS = 1500L

    /** The shortest cue on screen, unless the speaker's next cue starts before. */
    const val MIN_DURATION_MS = 1000L

    /** The longest line before a cue is split in two. */
    const val LINE_LENGTH = 42

    private const val SENTENCE_ENDS = ".?!…。？！"

    /**
     * The cues of [t]. Words are read in canonical order; a new cue starts before word `w` when (1) the speaker
     * changes, (2) the silence before `w` is at least [PAUSE_MS], (3) `w` would end more than [MAX_SPAN_MS] after the
     * cue start, (4) the text would be longer than [MAX_TEXT], or (5) the previous word ends a sentence and the cue
     * already lasts [SENTENCE_MS]. With [labelPrefixForSrt] and 2 or more speakers, rule (4) counts the SRT label
     * prefix in the first cue of each turn.
     *
     * A cue starts at its first word's start and ends at the latest end of its words, lengthened to
     * [MIN_DURATION_MS] but never past the start of the same speaker's next cue, so one speaker's cues never overlap.
     * Different speakers' cues may overlap. The cues are sorted by start with a stable sort, and the words inside a
     * cue keep their canonical order.
     *
     * With [labelPrefixForSrt] and 2 or more speakers, a cue gets the prefix when its speaker differs from the cue
     * before it in this sorted order (the first cue always does). Overlapping speakers can give a prefix to a cue that
     * rule (4) did not budget for; the line split then puts the extra text on the second line.
     */
    fun build(t: ExportTranscript, labelPrefixForSrt: Boolean): List<Cue> {
        val prefixed = labelPrefixForSrt && t.speakerCount >= 2
        val drafts = ArrayList<Draft>()
        var current: Draft? = null
        var prev: Word? = null
        for (word in t.words) {
            val cue = current
            if (cue == null || prev == null || breaksBefore(cue, prev, word)) {
                val budget = if (prefixed && prev?.speaker != word.speaker) prefixOf(t, word.speaker).length else 0
                current = Draft(word, budget).also { drafts += it }
            } else {
                cue.add(word)
            }
            prev = word
        }
        val sorted = drafts.sortedBy { it.startMs }
        val nextStarts = arrayOfNulls<Long>(sorted.size)
        val following = HashMap<String, Long>()
        for (index in sorted.indices.reversed()) {
            nextStarts[index] = following[sorted[index].speaker]
            following[sorted[index].speaker] = sorted[index].startMs
        }
        var shownSpeaker: String? = null
        return sorted.mapIndexed { index, draft ->
            val nextStart = nextStarts[index]
            val prefix = if (prefixed && draft.speaker != shownSpeaker) prefixOf(t, draft.speaker) else ""
            shownSpeaker = draft.speaker
            val text = draft.words.joinToString(" ") { it.text }
            Cue(
                speaker = draft.speaker,
                label = t.label(draft.speaker),
                startMs = draft.startMs,
                endMs = endOf(draft, nextStart),
                text = text,
                prefix = prefix,
                lines = lines(prefix + text, prefix.length),
            )
        }
    }

    /**
     * [text] as one line when it fits [LINE_LENGTH], else as two lines split at a space at or after [from]: the space
     * that best balances two lines of at most [LINE_LENGTH] (on a tie the shorter first line), else the last space
     * that keeps the first line within [LINE_LENGTH], else the first space. Text without such a space stays one line.
     * Words are never dropped and there are never three lines.
     */
    fun lines(text: String, from: Int = 0): List<String> {
        val spaces = (from until text.length).filter { text[it] == ' ' }
        if (text.length <= LINE_LENGTH || spaces.isEmpty()) return listOf(text)
        val balanced = spaces
            .filter { it <= LINE_LENGTH && text.length - it - 1 <= LINE_LENGTH }
            .minByOrNull { abs(text.length - 1 - 2 * it) }
        val at = balanced ?: spaces.lastOrNull { it <= LINE_LENGTH } ?: spaces.first()
        return listOf(text.substring(0, at), text.substring(at + 1))
    }

    private fun prefixOf(t: ExportTranscript, speaker: String): String = t.label(speaker) + ": "

    private fun breaksBefore(cue: Draft, prev: Word, word: Word): Boolean =
        word.speaker != prev.speaker ||
            word.startMs - prev.endMs >= PAUSE_MS ||
            word.endMs - cue.startMs > MAX_SPAN_MS ||
            cue.length + 1 + word.text.length > MAX_TEXT ||
            (prev.text.trimEnd().lastOrNull()?.let { it in SENTENCE_ENDS } == true && cue.endMs - cue.startMs >= SENTENCE_MS)

    private fun endOf(draft: Draft, nextStart: Long?): Long {
        val end = maxOf(draft.endMs, draft.startMs + MIN_DURATION_MS)
        return if (nextStart == null) end else minOf(end, nextStart)
    }

    private class Draft(first: Word, prefixLength: Int) {
        val speaker = first.speaker
        val startMs = first.startMs
        val words = arrayListOf(first)
        var endMs = first.endMs
            private set
        var length = prefixLength + first.text.length
            private set

        fun add(word: Word) {
            words += word
            endMs = maxOf(endMs, word.endMs)
            length += 1 + word.text.length
        }
    }
}
