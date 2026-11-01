package org.cassini.android.note

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.cassini.core.format.Word
import org.cassini.core.speakers.DefaultLabels
import org.cassini.core.speakers.Match
import org.cassini.core.speakers.NameMatch
import org.cassini.core.speakers.SpeakerPrint
import org.cassini.core.speakers.SpeakerTint
import org.cassini.core.speakers.Voice
import org.cassini.data.db.SpeakerEntity
import org.cassini.ui.components.SpeakerNameUi
import org.cassini.ui.components.SuggestionUi
import org.cassini.ui.components.TurnUi
import org.cassini.ui.components.WordRef
import org.cassini.ui.components.WordUi
import org.cassini.ui.theme.SpeakerTone
import java.util.Arrays

/**
 * The transcript the Note screen shows: its [turns] (one LazyColumn item each) and an [index] from audio time to
 * word, for the active word. Built off the main thread, once per transcript; the position ticks only search
 * [index].
 */
class NoteTranscript(val turns: ImmutableList<TurnUi>, internal val index: WordIndex) {
    /** The word playing at [positionMs]: the last word that started at or before it; null before the first. */
    fun activeWord(positionMs: Long): WordRef? = index.at(positionMs)

    /** Where turn [turnId] starts in the audio, or null for an unknown turn. */
    fun turnStart(turnId: String): Long? = turns.firstOrNull { it.id == turnId }?.startMs

    /** Where [word] starts in the audio. */
    fun wordStart(word: WordRef): Long? =
        turns.firstOrNull { it.id == word.turnId }?.words?.getOrNull(word.index)?.startMs

    /** The speakers in order of their first turn. */
    val speakers: List<String> get() = turns.map { it.speakerId }.distinct()

    companion object {
        val EMPTY = NoteTranscript(persistentListOf(), WordIndex(LongArray(0), emptyArray(), IntArray(0)))
    }
}

/** Word start times in canonical order, with the turn and the place in the turn of each word. */
class WordIndex(private val starts: LongArray, private val turnIds: Array<String>, private val inTurn: IntArray) {
    fun at(positionMs: Long): WordRef? {
        val found = Arrays.binarySearch(starts, positionMs)
        // binarySearch finds any of equal starts; the last of a run of equal starts is the one that plays.
        var index = if (found >= 0) found else -found - 2
        while (index >= 0 && index + 1 < starts.size && starts[index + 1] == starts[index]) index++
        return if (index < 0) null else WordRef(turnIds[index], inTurn[index])
    }
}

/**
 * The pure mapping from cached words (sealed notes) or the draft (a running or paused job) to [NoteTranscript]
 * (features.md sections 3.1 and 3.3; ui.md section 7, `TurnUi`).
 */
object NoteTranscripts {
    /**
     * A turn is split into paragraphs of at most this many words, so one long speaker (a single-voice hour) does not
     * make one huge item. A paragraph ends at a sentence end after [SOFT_TURN_WORDS] words.
     */
    const val MAX_TURN_WORDS = 200
    const val SOFT_TURN_WORDS = 120

    private const val SENTENCE_ENDS = ".?!…。？！"

    /** The colour of each speaker ([SpeakerTint] to the theme's tone). */
    fun tone(tint: SpeakerTint): SpeakerTone = when (tint) {
        SpeakerTint.BLUE -> SpeakerTone.Blue
        SpeakerTint.TEAL -> SpeakerTone.Teal
        SpeakerTint.VIOLET -> SpeakerTone.Violet
        SpeakerTint.AMBER -> SpeakerTone.Amber
    }

    /**
     * The name of a speaker row: the stored label verbatim (D47). A placeholder keeps its number (from the label, else
     * the first-speaking order); without a stored label it shows as "Speaker N" in the UI language (D53.3).
     */
    fun name(speaker: SpeakerEntity): SpeakerNameUi = if (speaker.isPlaceholder) {
        SpeakerNameUi.Placeholder(
            speaker.placeholderNumber ?: (speaker.ordinal + 1),
            DefaultLabels.stored(speaker.speakerId, speaker.label),
        )
    } else {
        SpeakerNameUi.Named(speaker.label)
    }

    /**
     * The sealed transcript of one variant: [words] in canonical order, the variant's [speakers], the speakers'
     * [prints] (their matches give the "auto" badge and the suggestion card) and the saved [voices].
     *
     * - "auto" shows when the link is AUTO and the label equals the voice's name.
     * - The suggestion card sits at a placeholder speaker's first turn when its link is SUGGESTED to a voice that
     *   still exists.
     */
    fun sealed(
        words: List<Word>,
        speakers: List<SpeakerEntity>,
        prints: Map<String, SpeakerPrint>,
        voices: List<Voice>,
    ): NoteTranscript {
        val byId = speakers.associateBy { it.speakerId }
        val order = words.map { it.speaker }.distinct()
        val names = order.associateWith { id -> byId[id]?.let(::name) ?: SpeakerNameUi.Placeholder(order.indexOf(id) + 1) }
        val tints = SpeakerTint.assign(order) { id -> names[id] is SpeakerNameUi.Placeholder }
        val voiceNames = voices.associate { it.id to it.name }
        val suggested = HashSet<String>()
        return build(words) { speaker, first ->
            val name = names.getValue(speaker)
            val match = prints[speaker]?.match
            val voiceName = match?.voiceId?.let(voiceNames::get)
            val auto = match?.state == Match.State.AUTO && voiceName != null && name is SpeakerNameUi.Named &&
                NameMatch.normalise(name.text) == NameMatch.normalise(voiceName)
            // The card sits at a placeholder speaker's first turn, once.
            val suggestion = match?.takeIf { it.state == Match.State.SUGGESTED && first }
                ?.takeIf { name is SpeakerNameUi.Placeholder && voiceName != null && suggested.add(speaker) }
                ?.let { SuggestionUi(it.voiceId, voiceName.orEmpty()) }
            TurnTemplate(name, tone(tints.getValue(speaker)), auto, suggestion)
        }
    }

    /**
     * The draft: [words] with the draft merges applied, [labels] by speaker (draft name, AUTO name, or a placeholder
     * label, shown verbatim; a speaker without one shows as "Speaker N" numbered by first word), [automatic] (the
     * "auto" badge), [suggestions] by speaker (the card sits at that speaker's most recent turn) and
     * [tentativeFromMs]: words that end after it may change (null for a paused draft, which has no unsettled words).
     */
    fun draft(
        words: List<Word>,
        labels: Map<String, String>,
        automatic: Set<String>,
        suggestions: Map<String, SuggestionUi>,
        tentativeFromMs: Long?,
    ): NoteTranscript {
        val order = words.map { it.speaker }.distinct()
        val names = order.withIndex().associate { (index, id) ->
            val label = labels[id]
            id to if (label == null || DefaultLabels.isDefault(id, label)) {
                SpeakerNameUi.Placeholder(index + 1, DefaultLabels.stored(id, label))
            } else {
                SpeakerNameUi.Named(label)
            }
        }
        val tints = SpeakerTint.assign(order) { id -> names[id] is SpeakerNameUi.Placeholder }
        val lastTurn = lastTurnStarts(words)
        val transcript = build(words) { speaker, _ ->
            TurnTemplate(names.getValue(speaker), tone(tints.getValue(speaker)))
        }
        val turns = transcript.turns.map { turn ->
            val name = turn.name
            val suggestion = suggestions[turn.speakerId]
                ?.takeIf { lastTurn[turn.speakerId] == turn.startMs && name is SpeakerNameUi.Placeholder }
            val unsettled = tentativeFromMs?.let { from -> unsettledFrom(words, turn, from) }
            turn.copy(auto = turn.speakerId in automatic, suggestion = suggestion, unsettledFrom = unsettled)
        }
        return NoteTranscript(turns.toImmutableList(), transcript.index)
    }

    // Building. ---------------------------------------------------------------------------------------------------

    private data class TurnTemplate(
        val name: SpeakerNameUi,
        val tone: SpeakerTone,
        val auto: Boolean = false,
        val suggestion: SuggestionUi? = null,
    )

    /** The turns of [words], each from [template] (speaker id, whether it is the speaker's first turn). */
    private fun build(words: List<Word>, template: (String, Boolean) -> TurnTemplate): NoteTranscript {
        val turns = ArrayList<TurnUi>()
        val starts = LongArray(words.size)
        val turnIds = arrayOfNulls<String>(words.size)
        val inTurn = IntArray(words.size)
        val seen = HashSet<String>()
        var from = 0
        while (from < words.size) {
            val speaker = words[from].speaker
            val to = paragraphEnd(words, from)
            val id = "w$from"
            val made = template(speaker, seen.add(speaker))
            val turnWords = ArrayList<WordUi>(to - from)
            for (i in from until to) {
                turnWords += WordUi(words[i].text, words[i].startMs)
                starts[i] = words[i].startMs
                turnIds[i] = id
                inTurn[i] = i - from
            }
            turns += TurnUi(
                id = id,
                speakerId = speaker,
                name = made.name,
                tone = made.tone,
                startMs = words[from].startMs,
                auto = made.auto,
                words = turnWords.toImmutableList(),
                suggestion = made.suggestion,
            )
            from = to
        }
        @Suppress("UNCHECKED_CAST") // every slot was filled above
        return NoteTranscript(turns.toImmutableList(), WordIndex(starts, turnIds as Array<String>, inTurn))
    }

    /** The end (exclusive) of the paragraph from [from]: the speaker's run, cut as [MAX_TURN_WORDS] says. */
    private fun paragraphEnd(words: List<Word>, from: Int): Int {
        val speaker = words[from].speaker
        var to = from + 1
        while (to < words.size && words[to].speaker == speaker && !paragraphFull(words, from, to)) to++
        return to
    }

    /** Whether the paragraph of words [from] until [to] is long enough to end before word [to]. */
    private fun paragraphFull(words: List<Word>, from: Int, to: Int): Boolean {
        val length = to - from
        val sentenceEnd = words[to - 1].text.lastOrNull()?.let { it in SENTENCE_ENDS } == true
        return length >= MAX_TURN_WORDS || (length >= SOFT_TURN_WORDS && sentenceEnd)
    }

    /** The start of each speaker's most recent paragraph. */
    private fun lastTurnStarts(words: List<Word>): Map<String, Long> {
        val result = HashMap<String, Long>()
        var from = 0
        while (from < words.size) {
            val to = paragraphEnd(words, from)
            result[words[from].speaker] = words[from].startMs
            from = to
        }
        return result
    }

    /** The first word of [turn] that ends after [fromMs], or null when all of it is settled. */
    private fun unsettledFrom(words: List<Word>, turn: TurnUi, fromMs: Long): Int? {
        val start = turn.id.removePrefix("w").toIntOrNull() ?: return null
        return turn.words.indices.firstOrNull { i -> words[start + i].endMs > fromMs }
    }
}
