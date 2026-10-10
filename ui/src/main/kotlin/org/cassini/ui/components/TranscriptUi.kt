package org.cassini.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import org.cassini.ui.R
import org.cassini.ui.theme.SpeakerTone

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

/**
 * A speaker's label as the transcript shows it (ui.md section 7): a [Named] label verbatim, or a [Placeholder]
 * number that the UI shows as "Speaker 3" / "Voce 3" (features.md section 0). The stored label is not changed.
 */
sealed interface SpeakerNameUi {
    @Immutable
    data class Named(val text: String) : SpeakerNameUi

    @Immutable
    data class Placeholder(val number: Int) : SpeakerNameUi
}

/** The label of this speaker in the shown language: the name, or "Speaker N". */
@Composable
@ReadOnlyComposable
fun SpeakerNameUi.display(): String = when (this) {
    is SpeakerNameUi.Named -> text
    is SpeakerNameUi.Placeholder -> stringResource(R.string.ui_speaker_label, number)
}

/** A saved voice that the matcher suggests for a placeholder speaker ("Is this **Luca**?"). */
@Immutable
data class SuggestionUi(val voiceId: String, val name: String)

/**
 * One transcript turn (ui.md section 7): the words of one speaker in a row. [startMs] is the turn header time.
 * [auto] shows the "auto" badge; [renamable] makes the header a button that opens the Name sheet. In the draft,
 * the words from [unsettledFrom] on may still change. [suggestion] puts the inline suggestion card at this turn.
 */
@Immutable
data class TurnUi(
    val id: String,
    val speakerId: String,
    val name: SpeakerNameUi,
    val tone: SpeakerTone,
    val startMs: Long,
    val auto: Boolean = false,
    val renamable: Boolean = true,
    val words: ImmutableList<WordUi>,
    val unsettledFrom: Int? = null,
    val suggestion: SuggestionUi? = null,
)

/** One word of the transcript: word [index] of the turn [turnId] (the active word, a word tap). */
@Immutable
data class WordRef(val turnId: String, val index: Int)
