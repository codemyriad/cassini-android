package org.cassini.core.library

/**
 * Text with highlighted [ranges] (indices into [text], end inclusive) and the ellipses that mark a cut at either end.
 * The ellipses are not part of [text].
 */
data class Highlighted(
    val text: String,
    val ranges: List<IntRange>,
    val leadingEllipsis: Boolean,
    val trailingEllipsis: Boolean,
)

/** The Library search rules (features.md section 1) over the FTS4 `note_fts` table. */
object SearchQuery {
    /** Start of a match in an FTS `snippet(note_fts, char(2), char(3), '…', 1, 15)`. */
    const val MATCH_START = '\u0002'

    /** End of a match in an FTS snippet. */
    const val MATCH_END = '\u0003'

    /** The ellipsis FTS writes where a snippet is cut. */
    const val ELLIPSIS = '…'

    private const val DROPPED = "\"*^:()-"

    /**
     * The FTS4 MATCH expression for what the user typed, or null when nothing searchable is left. Each
     * whitespace-separated term loses the characters `" * ^ : ( ) -` and becomes a quoted prefix phrase with the star
     * INSIDE the quotes (`"consegn*"`, the FTS3/4 syntax; a star after the closing quote is FTS5 syntax). The phrases
     * are joined with spaces, an implicit AND, so AND, OR, NOT and NEAR typed by the user are plain words.
     *
     * A term with no letter or digit is dropped too: the unicode61 tokenizer makes no token of it, and FTS4 matches
     * nothing at all for a query that contains such an empty phrase, so `consegna ,` would find nothing.
     */
    fun fts(input: String): String? {
        val phrases = terms(input)
            .map { term -> term.filterNot { it in DROPPED } }
            .filter { term -> term.codePoints().anyMatch(Character::isLetterOrDigit) }
            .map { "\"$it*\"" }
        return phrases.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    /**
     * Turns an FTS [snippet] into [Highlighted]: the `char(2)`/`char(3)` markers become ranges and never reach the
     * text, and an ellipsis at either end becomes a flag. An unclosed match runs to the end; a stray end marker and an
     * empty match are ignored. A stored Library preview (no markers) parses the same way.
     */
    fun highlights(snippet: String): Highlighted {
        val leading = snippet.startsWith(ELLIPSIS)
        val trailing = snippet.length > (if (leading) 1 else 0) && snippet.endsWith(ELLIPSIS)
        val body = snippet.substring(if (leading) 1 else 0, snippet.length - if (trailing) 1 else 0)
        val text = StringBuilder(body.length)
        val ranges = mutableListOf<IntRange>()
        var openAt = -1
        for (char in body) {
            when (char) {
                MATCH_START -> if (openAt < 0) openAt = text.length
                MATCH_END -> {
                    if (openAt in 0 until text.length) ranges.add(openAt until text.length)
                    openAt = -1
                }
                else -> text.append(char)
            }
        }
        if (openAt in 0 until text.length) ranges.add(openAt until text.length)
        return Highlighted(text.toString(), ranges, leading, trailing)
    }

    /** Splits on any Unicode whitespace (no-break spaces included), dropping empty pieces. */
    private fun terms(input: String): List<String> {
        val terms = mutableListOf<String>()
        val term = StringBuilder()
        for (char in input) {
            if (!char.isWhitespace()) {
                term.append(char)
            } else if (term.isNotEmpty()) {
                terms.add(term.toString())
                term.clear()
            }
        }
        if (term.isNotEmpty()) terms.add(term.toString())
        return terms
    }
}
