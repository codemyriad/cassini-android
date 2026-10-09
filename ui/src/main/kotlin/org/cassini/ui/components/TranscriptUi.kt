package org.cassini.ui.components

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** One transcript word: its [text] as the paragraph shows it (with its punctuation) and where it starts in the audio. */
@Immutable
data class WordUi(val text: String, val startMs: Long)

/** How a transcript is shown: [Read] is the sealed transcript of Read & listen, [Draft] the Transcribing draft. */
enum class TurnMode { Read, Draft }

/**
 * Search hits in one paragraph: [ranges] are character ranges of [turnText] (end exclusive is `last + 1`), in text
 * order; [current] indexes the current match in [ranges], when it is in this paragraph.
 */
@Immutable
data class TurnMatches(val ranges: ImmutableList<IntRange>, val current: Int? = null) {
    companion object {
        val None: TurnMatches = TurnMatches(persistentListOf())
    }
}

/**
 * The text of a paragraph: the words joined by single spaces. [TurnMatches] ranges index into this string, so
 * transcript search must match against it.
 */
fun turnText(words: List<WordUi>): String = words.joinToString(" ") { it.text }

/** The start offset of each word in [turnText]. */
internal fun wordStarts(words: List<WordUi>): IntArray {
    val starts = IntArray(words.size)
    var offset = 0
    words.forEachIndexed { index, word ->
        starts[index] = offset
        offset += word.text.length + 1
    }
    return starts
}
