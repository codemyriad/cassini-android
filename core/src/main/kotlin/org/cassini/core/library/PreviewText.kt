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
 * When the saved playback position is after the first word, the excerpt starts at the word playing at that position
 * and has a leading ellipsis. It takes whole words up to about [LIMIT] characters and stops at the end of the
 * speaker's turn, so one preview never runs two speakers' words together: board 00 shows Paolo's turn from "quindi"
 * (board 03 at 11:44) and stops before Speaker 3, although that text is shorter than [LIMIT]. Otherwise the excerpt is
 * the transcript start: the first [LIMIT] characters of the words joined by spaces (`note_text.body`), cut at a word.
 *
 * An excerpt that ends before the transcript end gets a trailing ellipsis, and the sentence punctuation at its end is
 * dropped first ("order." becomes "order…").
 */
object PreviewText {
    /** The excerpt budget in UTF-16 units, without the ellipses. */
    const val LIMIT = 160

    private const val CUT_PUNCTUATION = ".,;:!?…"

    /** The preview of [words] (one transcript variant, canonical order) at [positionMs]; null without words. */
    fun of(words: List<Word>, positionMs: Long): Preview? {
        if (words.isEmpty()) return null
        val first = playing(words, positionMs)
        val end = if (first == 0) words.size else turnEnd(words, first)
        val text = StringBuilder()
        var next = first
        while (next < end && (next == first || text.length + 1 + words[next].text.length <= LIMIT)) {
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
     * that is the word itself; in a pause it is the next word to play; after the last word, the last word.
     */
    private fun playing(words: List<Word>, positionMs: Long): Int =
        words.indexOfFirst { it.endMs > positionMs }.let { if (it < 0) words.lastIndex else it }

    private fun turnEnd(words: List<Word>, from: Int): Int {
        val speaker = words[from].speaker
        var end = from + 1
        while (end < words.size && words[end].speaker == speaker) end++
        return end
    }

    /** [text] cut to at most [limit] UTF-16 units, never inside a surrogate pair (only one giant word needs it). */
    private fun cutAt(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val end = if (Character.isHighSurrogate(text[limit - 1])) limit - 1 else limit
        return text.substring(0, end)
    }
}
