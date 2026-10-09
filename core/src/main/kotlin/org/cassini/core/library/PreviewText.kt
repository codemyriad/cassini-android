package org.cassini.core.library

import org.cassini.core.format.Word

/**
 * A Library preview excerpt. [text] has no ellipses; [leadingEllipsis] marks an excerpt that does not start at the
 * transcript start, [trailingEllipsis] one that was cut before the transcript end.
 */
data class Preview(val text: String, val leadingEllipsis: Boolean, val trailingEllipsis: Boolean) {
    /** The excerpt as shown and stored in `notes.preview`; [SearchQuery.highlights] reads it back. */
    val display: String get() = (if (leadingEllipsis) "…" else "") + text + (if (trailingEllipsis) "…" else "")
}

/**
 * The Library preview of a transcript without a search query (features.md section 1).
 *
 * When the saved playback position is after the first word and before the end of the last word, the excerpt starts
 * at the word playing at that position and has a leading ellipsis. It takes whole words up to about [LIMIT]
 * characters and stops at the first end of a speaker's turn reached once it holds at least [MIN_BEFORE_TURN_END]
 * characters, so one preview does not run two speakers' words together: board 00 shows Paolo's turn from "quindi"
 * (board 03 at 11:44) and stops before Speaker 3, although that text is shorter than [LIMIT]. A shorter turn ("Sì.")
 * continues into the next turns, so the row never shows a one-word excerpt while more words follow.
 *
 * Otherwise (position 0, before or in the first word, or at or after the end of the last word, which is where a
 * finished playback leaves it) the excerpt is the transcript start: the first [LIMIT] characters of the words joined
 * by spaces (`note_text.body`), cut at a word.
 *
 * An excerpt that ends before the transcript end gets a trailing ellipsis, and the sentence punctuation at its end is
 * dropped first ("order." becomes "order…").
 */
object PreviewText {
    /** The excerpt budget in UTF-16 units, without the ellipses. */
    const val LIMIT = 160

    /** An excerpt from the playing word stops at a turn end only once it has at least this many UTF-16 units. */
    const val MIN_BEFORE_TURN_END = 40

    private const val CUT_PUNCTUATION = ".,;:!?…"

    /** The preview of [words] (one transcript variant, canonical order) at [positionMs]; null without words. */
    fun of(words: List<Word>, positionMs: Long): Preview? {
        if (words.isEmpty()) return null
        val first = if (positionMs >= words.last().endMs) 0 else playing(words, positionMs)
        val text = StringBuilder()
        var next = first
        while (next < words.size && (next == first || text.length + 1 + words[next].text.length <= LIMIT)) {
            if (first > 0 && next > first && stopsBefore(words, next, text.length)) break
            if (next > first) text.append(' ')
            text.append(words[next].text)
            next++
        }
        val cut = next < words.size
        var shown = cutAt(text.toString(), LIMIT)
        if (cut) shown = shown.trimEnd { it in CUT_PUNCTUATION }.ifEmpty { shown }
        return Preview(shown, leadingEllipsis = first > 0, trailingEllipsis = cut || shown.length < text.length)
    }

    /**
     * The word playing at [positionMs]: the first word, in canonical order, that has not ended there. Within a word
     * that is the word itself; in a pause it is the next word to play. [of] handles a position after the last word.
     */
    private fun playing(words: List<Word>, positionMs: Long): Int =
        words.indexOfFirst { it.endMs > positionMs }

    /** Whether an excerpt of [length] units ends before [index], which starts another speaker's turn. */
    private fun stopsBefore(words: List<Word>, index: Int, length: Int) =
        length >= MIN_BEFORE_TURN_END && words[index].speaker != words[index - 1].speaker

    /** [text] cut to at most [limit] UTF-16 units, never inside a surrogate pair (only one giant word needs it). */
    private fun cutAt(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val end = if (Character.isHighSurrogate(text[limit - 1])) limit - 1 else limit
        return text.substring(0, end)
    }
}
