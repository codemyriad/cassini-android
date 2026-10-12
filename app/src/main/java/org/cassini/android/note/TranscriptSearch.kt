package org.cassini.android.note

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import org.cassini.ui.components.TurnUi
import org.cassini.ui.components.turnText
import org.cassini.ui.note.TranscriptMatch
import java.text.Normalizer
import java.util.Locale

/**
 * In-note search (features.md section 3.3): case- and diacritic-insensitive matching over each turn's paragraph
 * text (`turnText`), whose character ranges the screen highlights. "giovedi" finds "giovedì", "CONSEGNA" finds
 * "consegna".
 */
object TranscriptSearch {
    private val combining = Regex("\\p{Mn}+")
    private const val ASCII_END = 0x80

    /** Every match of [query] in [turns], in text order. A blank query matches nothing. */
    fun matches(turns: List<TurnUi>, query: String): ImmutableList<TranscriptMatch> {
        val needle = fold(query.trim()).text
        if (needle.isEmpty()) return emptyList<TranscriptMatch>().toImmutableList()
        val result = ArrayList<TranscriptMatch>()
        for (turn in turns) {
            val folded = fold(turnText(turn.words))
            var at = folded.text.indexOf(needle)
            while (at >= 0) {
                result += TranscriptMatch(turn.id, folded.start[at]..folded.end[at + needle.length - 1])
                at = folded.text.indexOf(needle, at + needle.length)
            }
        }
        return result.toImmutableList()
    }

    /**
     * [text] decomposed (NFD) without combining marks and lower-cased. [start] and [end] give, for each char of the
     * result, the first and last index of the source char it came from.
     */
    internal class Folded(val text: String, val start: IntArray, val end: IntArray)

    internal fun fold(text: String): Folded {
        val out = StringBuilder(text.length)
        val starts = ArrayList<Int>(text.length)
        val ends = ArrayList<Int>(text.length)
        var i = 0
        while (i < text.length) {
            val ascii = text[i]
            if (ascii.code < ASCII_END) {
                out.append(ascii.lowercaseChar())
                starts += i
                ends += i
                i++
                continue
            }
            val size = Character.charCount(text.codePointAt(i))
            val piece = Normalizer.normalize(text.substring(i, i + size), Normalizer.Form.NFD)
                .replace(combining, "")
                .lowercase(Locale.ROOT)
            for (c in piece) {
                out.append(c)
                starts += i
                ends += i + size - 1
            }
            // A combining mark alone folds to nothing: it belongs to the char before it.
            if (piece.isEmpty() && ends.isNotEmpty()) ends[ends.lastIndex] = i + size - 1
            i += size
        }
        return Folded(out.toString(), starts.toIntArray(), ends.toIntArray())
    }
}
