package io.codemyriad.cassini.note

import io.codemyriad.cassini.core.export.ReadingText
import io.codemyriad.cassini.core.export.ReadingTurns
import io.codemyriad.cassini.core.format.Word
import io.codemyriad.cassini.core.speakers.DefaultLabels
import io.codemyriad.cassini.core.speakers.Match
import io.codemyriad.cassini.core.speakers.NameMatch
import io.codemyriad.cassini.core.speakers.SpeakerPrint
import io.codemyriad.cassini.core.speakers.SpeakerTint
import io.codemyriad.cassini.core.speakers.Voice
import io.codemyriad.cassini.data.db.SpeakerEntity
import io.codemyriad.cassini.ui.components.InlineSuggestionUi
import io.codemyriad.cassini.ui.components.SpeakerNameUi
import io.codemyriad.cassini.ui.components.SuggestionUi
import io.codemyriad.cassini.ui.components.TurnUi
import io.codemyriad.cassini.ui.components.WordRef
import io.codemyriad.cassini.ui.components.WordUi
import io.codemyriad.cassini.ui.theme.SpeakerTone
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import java.util.Arrays

/** Naming metadata for every speaker, including speakers shown only inside a chip. */
data class TranscriptSpeaker(val name: SpeakerNameUi, val tone: SpeakerTone)

/**
 * The transcript the Note screen shows: its [turns] (one LazyColumn item each) and an [index] from audio time to
 * word, for the active word. Built off the main thread, once per transcript; the position ticks only search
 * [index].
 */
class NoteTranscript(
    val turns: ImmutableList<TurnUi>,
    internal val index: WordIndex,
    val speakerMetadata: Map<String, TranscriptSpeaker> = emptyMap(),
) {
    /** The word playing at [positionMs]: the last word that started at or before it; null before the first. */
    fun activeWord(positionMs: Long): WordRef? = index.at(positionMs)

    /** Where turn [turnId] starts in the audio, or null for an unknown turn. */
    fun turnStart(turnId: String): Long? = turns.firstOrNull { it.id == turnId }?.startMs

    /** Where [word] starts in the audio. */
    fun wordStart(word: WordRef): Long? =
        turns.firstOrNull { it.id == word.turnId }?.words?.getOrNull(word.index)?.startMs

    /** The speakers in order of their first turn. */
    val speakers: List<String> get() = turns.flatMap { turn ->
        turn.words.map { it.speakerId.ifEmpty { turn.speakerId } }
    }.distinct()

    companion object {
        val EMPTY = NoteTranscript(persistentListOf(), WordIndex(LongArray(0), emptyArray(), IntArray(0)))
    }
}

/** Word starts sorted by time, with canonical order breaking ties, mapped to explicit display references. */
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
    @Suppress("LongParameterList") // Shared words, names, matches and display provenance.
    fun sealed(
        words: List<Word>,
        speakers: List<SpeakerEntity>,
        prints: Map<String, SpeakerPrint>,
        voices: List<Voice>,
        placeholderLabel: (Int) -> String = { "Speaker $it" },
        endsBoundedByAudio: Boolean = false,
    ): NoteTranscript {
        val byId = speakers.associateBy { it.speakerId }
        val order = words.map { it.speaker }.distinct()
        val names = order.associateWith { id -> byId[id]?.let(::name) ?: SpeakerNameUi.Placeholder(order.indexOf(id) + 1) }
        val tints = SpeakerTint.assign(order) { id -> names[id] is SpeakerNameUi.Placeholder }
        val voiceNames = voices.associate { it.id to it.name }
        val suggested = HashSet<String>()
        return build(words, placeholderLabel, endsBoundedByAudio) { speaker, first ->
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
    @Suppress("LongParameterList") // Shared words, draft state and localized fallback labels.
    fun draft(
        words: List<Word>,
        labels: Map<String, String>,
        automatic: Set<String>,
        suggestions: Map<String, SuggestionUi>,
        tentativeFromMs: Long?,
        placeholderLabel: (Int) -> String = { "Speaker $it" },
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
        val transcript = build(words, placeholderLabel, false) { speaker, _ ->
            TurnTemplate(names.getValue(speaker), tone(tints.getValue(speaker)))
        }
        val lastTurn = transcript.turns.flatMap { turn ->
            turn.words.map { word -> Triple(word.speakerId, word.startMs, turn.id) }
        }.groupBy { it.first }.mapValues { (_, appearances) -> appearances.maxBy { it.second }.third }
        val turns = transcript.turns.map { turn ->
            val name = turn.name
            val suggestion = suggestions[turn.speakerId]
                ?.takeIf { lastTurn[turn.speakerId] == turn.id && name is SpeakerNameUi.Placeholder }
            val updatedWords = turn.words.map { word ->
                word.copy(unsettled = tentativeFromMs != null && words[word.canonicalIndex].endMs > tentativeFromMs)
            }
            val unsettled = updatedWords.indexOfFirst { it.unsettled }.takeIf { it >= 0 }
            val inline = turn.words.distinctBy { it.speakerId }.filter { it.speakerId != turn.speakerId }
                .mapNotNull { word ->
                    val id = word.speakerId
                    val offered = suggestions[id]?.takeIf { lastTurn[id] == turn.id && names[id] is SpeakerNameUi.Placeholder }
                    offered?.let {
                        InlineSuggestionUi(
                            id,
                            displayName(names.getValue(id), placeholderLabel),
                            tone(tints.getValue(id)),
                            word.startMs,
                            it,
                        )
                    }
                }
            turn.copy(
                auto = turn.speakerId in automatic,
                suggestion = suggestion,
                unsettledFrom = unsettled,
                words = updatedWords.toImmutableList(),
                inlineSuggestions = inline.toImmutableList(),
            )
        }
        return NoteTranscript(turns.toImmutableList(), transcript.index, transcript.speakerMetadata)
    }

    // Building. ---------------------------------------------------------------------------------------------------

    private data class TurnTemplate(
        val name: SpeakerNameUi,
        val tone: SpeakerTone,
        val auto: Boolean = false,
        val suggestion: SuggestionUi? = null,
    )

    /** Build rows and explicit canonical-to-display references; a rejoined row need not own a contiguous range. */
    // One pass maps viewer members, naming cards and canonical playback references.
    @Suppress("LongMethod", "CyclomaticComplexMethod", "ComplexCondition")
    private fun build(
        words: List<Word>,
        placeholderLabel: (Int) -> String,
        endsBoundedByAudio: Boolean,
        template: (String, Boolean) -> TurnTemplate,
    ): NoteTranscript {
        val reading = ReadingTurns.of(words, endsBoundedByAudio)
        val seen = HashSet<String>()
        val templates = HashMap<String, TurnTemplate>()
        fun made(speaker: String) = templates.getOrPut(speaker) { template(speaker, seen.add(speaker)) }
        val refs = arrayOfNulls<WordRef>(words.size)
        val offered = HashSet<String>()
        val turns = reading.rows.map { row ->
            val speaker = made(row.speaker)
            val id = "w${row.key}"
            val inline = ArrayList<InlineSuggestionUi>()
            val turnWords = ArrayList<WordUi>()
            for (member in row.members) {
                val chipSpeaker = member.interjectionSpeaker
                val chip = chipSpeaker?.let(::made)
                val label = chip?.let { displayName(it.name, placeholderLabel) }
                if (chipSpeaker != null && chip != null && chip.suggestion != null && offered.add(chipSpeaker)) {
                    inline += InlineSuggestionUi(
                        chipSpeaker,
                        label.orEmpty(),
                        chip.tone,
                        reading.words[member.wordIndices.first()].startMs,
                        chip.suggestion,
                    )
                }
                for ((index, canonical) in member.wordIndices.withIndex()) {
                    val word = reading.words[canonical]
                    refs[canonical] = WordRef(id, turnWords.size)
                    turnWords += WordUi(
                        text = word.text,
                        startMs = word.startMs,
                        canonicalIndex = canonical,
                        endMs = word.endMs,
                        speakerId = word.speaker,
                        prefix = if (label != null && index == 0) "($label: " else "",
                        suffix = if (label != null && index == member.wordIndices.lastIndex) ")" else "",
                        interjectionTone = chip?.tone,
                    )
                }
            }
            val over = ReadingText.over(row) { displayName(made(it).name, placeholderLabel) }
            TurnUi(
                id = id,
                speakerId = row.speaker,
                name = speaker.name,
                tone = speaker.tone,
                startMs = row.startMs,
                auto = speaker.auto,
                words = turnWords.toImmutableList(),
                suggestion = speaker.suggestion?.takeIf { row.key == row.turnKey && offered.add(row.speaker) },
                continuation = row.continuation,
                over = over,
                crosstalk = row.crosstalk,
                inlineSuggestions = inline.toImmutableList(),
            )
        }
        val chronological = words.indices.sortedWith(compareBy<Int> { words[it].startMs }.thenBy { it })
        val starts = chronological.map { words[it].startMs }.toLongArray()
        val turnIds = chronological.map { checkNotNull(refs[it]).turnId }.toTypedArray()
        val inTurn = chronological.map { checkNotNull(refs[it]).index }.toIntArray()
        val metadata = words.map { it.speaker }.distinct().associateWith { id ->
            made(id).let { TranscriptSpeaker(it.name, it.tone) }
        }
        return NoteTranscript(turns.toImmutableList(), WordIndex(starts, turnIds, inTurn), metadata)
    }

    private fun displayName(name: SpeakerNameUi, placeholder: (Int) -> String): String = when (name) {
        is SpeakerNameUi.Named -> name.text
        is SpeakerNameUi.Placeholder -> name.stored ?: placeholder(name.number)
    }
}
