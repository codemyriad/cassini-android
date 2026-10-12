package org.cassini.android.note

import kotlinx.collections.immutable.toImmutableList
import org.cassini.core.models.ModelCatalog
import org.cassini.core.speakers.Match
import org.cassini.core.speakers.NameMatch
import org.cassini.core.speakers.SampleWindow
import org.cassini.core.speakers.Voice
import org.cassini.core.speakers.VoiceStaleness
import org.cassini.core.speakers.Voiceprints
import org.cassini.ui.components.SpeakerNameUi
import org.cassini.ui.note.MergeTargetUi
import org.cassini.ui.note.MergeUi
import org.cassini.ui.note.MergeVersionsUi
import org.cassini.ui.note.NameSheetUi
import org.cassini.ui.note.RememberReason
import org.cassini.ui.note.SaveState
import org.cassini.ui.note.VoiceOptionUi
import org.cassini.ui.theme.SpeakerTone

/**
 * What the Name a speaker sheet knows about one speaker when it opens (features.md section 4).
 *
 * - [label] is the label as shown; a [SpeakerNameUi.Named] label is also the stored label.
 * - [draft] is true while the job runs or is paused (naming open): the save goes to `DraftNames`.
 * - [print] is the speaker's voiceprint (sealed notes only); [printSeconds] its speech. [willHavePrint] says that a
 *   draft speaker gets a print at the end of the job (the voiceprint model is installed).
 * - [match] is the speaker's link (sealed) or live match (draft) to a saved voice, with [autoName] true when the
 *   label comes from an AUTO match.
 * - [voices] are the saved people; [voicesReadOnly] when `people.json` cannot be changed.
 * - [mergeTargets] are the other speakers of the shown transcript; [sealedVersions] the versions of a sealed merge.
 * - [canSample] is false when no player is open (the Transcribing mode has none).
 */
data class NamingInput(
    val speakerId: String,
    val label: SpeakerNameUi,
    val tone: SpeakerTone,
    val speakingMs: Long,
    val sample: SampleWindow?,
    val draft: Boolean,
    val print: FloatArray? = null,
    val printSeconds: Double = 0.0,
    val willHavePrint: Boolean = false,
    val match: Match? = null,
    val voices: List<Voice> = emptyList(),
    val voicesReadOnly: Boolean = false,
    val voiceModelReady: Boolean = true,
    val mergeTargets: List<MergeTargetUi> = emptyList(),
    val sealedVersions: MergeVersionsUi? = null,
    val canSample: Boolean = true,
) {
    /** The stored name, or null for a placeholder speaker. */
    val namedLabel: String? get() = (label as? SpeakerNameUi.Named)?.text

    /** A print exists for the same-person question and the Remember checkbox. */
    val hasPrint: Boolean get() = if (draft) willHavePrint else print != null

    @Suppress("CyclomaticComplexMethod") // field by field, with the print compared by content
    override fun equals(other: Any?): Boolean = this === other ||
        other is NamingInput && speakerId == other.speakerId && label == other.label && tone == other.tone &&
        speakingMs == other.speakingMs && sample == other.sample && draft == other.draft &&
        print contentEquals other.print && printSeconds == other.printSeconds &&
        willHavePrint == other.willHavePrint && match == other.match && voices == other.voices &&
        voicesReadOnly == other.voicesReadOnly && voiceModelReady == other.voiceModelReady &&
        mergeTargets == other.mergeTargets && sealedVersions == other.sealedVersions && canSample == other.canSample

    override fun hashCode(): Int = speakerId.hashCode() * HASH + label.hashCode()

    private companion object {
        const val HASH = 31
    }
}

/**
 * The open sheet: the typed [name], the saved voice it is bound to ([boundVoiceId]), the Remember checkbox, the
 * answers given so far, and the Merge step ([merge]: null while the Name sheet shows; an empty [MergeStep] for the
 * list; a target for the confirmation). [initialName] and [initialBound] are the values at open, for the Save rule.
 */
data class NamingState(
    val input: NamingInput,
    val name: String,
    val boundVoiceId: String?,
    val remember: Boolean,
    val initialName: String,
    val initialBound: String?,
    val initialRemember: Boolean,
    val showAll: Boolean = false,
    /** The normalised name the user answered "Someone else" to: the question stays closed for it. */
    val declinedSamePerson: String? = null,
    /** The answer to "Also rename the saved voice?": true renames it, false keeps it (someone else). */
    val renameAnswer: Boolean? = null,
    val merge: MergeStep? = null,
)

/** The Merge sheet: [target] null shows the list of speakers, a speaker id shows the confirmation. */
data class MergeStep(val target: String? = null)

/** What the sheet asks for (features.md section 4). The screen's events map onto these. */
sealed interface NamingEvent {
    data class NameChanged(val text: String) : NamingEvent

    data class PickVoice(val voiceId: String) : NamingEvent

    data object ShowAll : NamingEvent

    /** "Same person" in the same-person question. */
    data object SamePerson : NamingEvent

    /** "Someone else" in the same-person question: Save makes a new voice. */
    data object SomeoneElse : NamingEvent

    /** "Rename saved voice" in the rename-saved question. */
    data object RenameSaved : NamingEvent

    /** "Someone else" in the rename-saved question: the saved voice keeps its name, this speaker is someone else. */
    data object KeepSaved : NamingEvent

    /** "Not <name>?". */
    data object NotThisPerson : NamingEvent

    data class RememberToggled(val remember: Boolean) : NamingEvent

    data object PlaySample : NamingEvent

    /** "Merge into…": the list of the other speakers. */
    data object MergeInto : NamingEvent

    /** A row of the Merge list: the confirmation for [speakerId]. */
    data class MergeTarget(val speakerId: String) : NamingEvent

    /** The confirmation's Cancel: back to the list. */
    data object CancelMerge : NamingEvent

    data object ConfirmMerge : NamingEvent

    data object Save : NamingEvent

    data object Cancel : NamingEvent
}

/** What the ViewModel carries out for the sheet, in list order. */
sealed interface NamingEffect {
    /** Sealed: write [label] for [speakerId] into the document. */
    data class Relabel(val speakerId: String, val label: String) : NamingEffect

    /** Sealed: enrol the speaker's print into [voiceId] (a new person when null) called [name]; CONFIRMED. */
    data class RememberVoice(val speakerId: String, val voiceId: String?, val name: String) : NamingEffect

    /** Sealed: rename the saved voice [voiceId] to [name] (the rename-saved question answered yes). */
    data class RenameVoice(val voiceId: String, val name: String) : NamingEffect

    /** Sealed: drop an AUTO match to a voice this speaker is no longer called. */
    data class ClearMatch(val speakerId: String) : NamingEffect

    /** Sealed: take back the CONFIRMED enrolment of [speakerId] (it was confirmed as someone else). */
    data class Unenrol(val speakerId: String) : NamingEffect

    /**
     * "Not <name>": [voiceId] is rejected for [speakerId]; [unenrol] takes a CONFIRMED enrolment back first, and
     * [restorePlaceholder] returns a label equal to that name to "Speaker N". In a draft it is `DraftNames.reject`.
     */
    data class Reject(
        val speakerId: String,
        val voiceId: String,
        val voiceName: String,
        val unenrol: Boolean,
        val restorePlaceholder: Boolean,
        val draft: Boolean,
    ) : NamingEffect

    /** Sealed: merge [source] into [target]; this adds a MERGED version. */
    data class MergeSealed(val source: String, val target: String) : NamingEffect

    /** Draft: merge [source] into [target] in `names.json`; [targetLabel] is the target's name, if it has one. */
    data class MergeDraft(val source: String, val target: String, val targetLabel: String?) : NamingEffect

    /**
     * Draft save: name [speakerId] [name]. [voiceId] is the Remember choice (a saved voice, "" for a new one, null
     * for none); [confirm] binds the saved voice at once ("Yes, Luca" through the sheet) with [score].
     */
    data class DraftName(
        val speakerId: String,
        val name: String,
        val voiceId: String?,
        val confirm: Boolean,
        val score: Float,
    ) : NamingEffect

    /** Play the speaker sample. */
    data class PlayRange(val startMs: Long, val endMs: Long) : NamingEffect

    /** Save was pressed with a question open: focus the question and say "Answer the question above first". */
    data object FocusQuestion : NamingEffect

    /** Close the sheet. */
    data object Close : NamingEffect
}

/** The sheet after an event ([state] null once it closed) and the effects to carry out. */
data class NamingResult(val state: NamingState?, val effects: List<NamingEffect> = emptyList())

/**
 * The pure reducer of the Name a speaker sheet (features.md section 4; architecture section 6): events in, sheet
 * state and effects out. The ViewModel owns one [NamingState] while the sheet is open, builds the [NameSheetUi] and
 * [MergeUi] with [ui] and [mergeUi], and carries out the [NamingEffect]s through the repositories (sealed notes) or
 * `coordinator.draft` (drafts).
 *
 * The sealed save follows 0.1.0 `rememberVoice`: a CONFIRMED voice that is no longer this speaker is unenrolled, a
 * stale AUTO match is cleared, then the print is enrolled into the bound or a new voice (CONFIRMED with its score).
 */
@Suppress("TooManyFunctions") // one handler per sheet event
object SpeakerNaming {
    /** The longest name the field takes. */
    const val MAX_NAME = 80

    /** Remember needs at least this much speech (features.md section 4, item 7). */
    const val MIN_REMEMBER_MS = 4_000L

    /** Rows of saved voices before "Show all". */
    const val VISIBLE_VOICES = 3

    /** The sheet at open: the field holds the label (empty for a placeholder), bound to a matching saved voice. */
    fun open(input: NamingInput): NamingState {
        val name = input.namedLabel.orEmpty()
        val linked = input.match?.takeIf { it.state == Match.State.AUTO || it.state == Match.State.CONFIRMED }
        val voice = linked?.let { match -> input.voices.firstOrNull { it.id == match.voiceId } }
        // A CONFIRMED voice is this speaker even when the label was written differently; an AUTO one only by name.
        val bound = voice?.takeIf { linked?.state == Match.State.CONFIRMED || same(it.name, name) }?.id
        val remember = rememberReason(input) == null && !input.voicesReadOnly
        return NamingState(input, name, bound, remember, name, bound, remember)
    }

    /** One event. */
    @Suppress("CyclomaticComplexMethod") // one branch per event of features.md section 4
    fun reduce(state: NamingState, event: NamingEvent): NamingResult = when (event) {
        is NamingEvent.NameChanged -> NamingResult(nameChanged(state, event.text))
        is NamingEvent.PickVoice -> NamingResult(pick(state, event.voiceId))
        NamingEvent.ShowAll -> NamingResult(state.copy(showAll = true))
        NamingEvent.SamePerson -> NamingResult(samePerson(state)?.let { pick(state, it.id) } ?: state)
        NamingEvent.SomeoneElse -> NamingResult(state.copy(declinedSamePerson = NameMatch.normalise(state.name)))
        NamingEvent.RenameSaved -> NamingResult(state.copy(renameAnswer = true, remember = rememberable(state)))
        NamingEvent.KeepSaved -> NamingResult(state.copy(renameAnswer = false))
        NamingEvent.NotThisPerson -> notThisPerson(state)
        is NamingEvent.RememberToggled -> NamingResult(state.copy(remember = event.remember && rememberable(state)))
        NamingEvent.PlaySample -> NamingResult(state, playSample(state))
        NamingEvent.MergeInto -> NamingResult(if (canMerge(state)) state.copy(merge = MergeStep()) else state)
        is NamingEvent.MergeTarget -> NamingResult(mergeTarget(state, event.speakerId))
        NamingEvent.CancelMerge -> NamingResult(state.copy(merge = state.merge?.copy(target = null)))
        NamingEvent.ConfirmMerge -> confirmMerge(state)
        NamingEvent.Save -> save(state)
        NamingEvent.Cancel -> NamingResult(null, listOf(NamingEffect.Close))
    }

    // The sheet as the screen shows it. ---------------------------------------------------------------------------

    /** The Name sheet of [state]; [sampling] while the sample plays. */
    fun ui(state: NamingState, sampling: Boolean = false): NameSheetUi {
        val input = state.input
        val ranked = rankedVoices(input)
        val shown = if (state.showAll) ranked else ranked.take(VISIBLE_VOICES)
        val reason = rememberReason(input)
        return NameSheetUi(
            speakerId = input.speakerId,
            label = input.label,
            tone = input.tone,
            speakingMs = input.speakingMs,
            canSample = input.sample != null && input.canSample,
            sampling = sampling,
            name = state.name,
            voices = shown.map { VoiceOptionUi(it.voice.id, it.voice.name, it.likely) }.toImmutableList(),
            showAll = !state.showAll && ranked.size > VISIBLE_VOICES,
            ranked = input.print != null,
            samePerson = samePerson(state)?.name,
            renameSaved = renameQuestion(state)?.name,
            notName = notName(input)?.name,
            remember = state.remember && reason == null,
            rememberEnabled = reason == null && !input.voicesReadOnly,
            rememberReason = reason,
            canMerge = canMerge(state),
            save = saveState(state),
            draft = input.draft,
        )
    }

    /** The Merge sheet of [state], or null while the Name sheet shows. */
    fun mergeUi(state: NamingState): MergeUi? {
        val step = state.merge ?: return null
        val targets = state.input.mergeTargets
        return MergeUi(
            sourceId = state.input.speakerId,
            source = state.input.label,
            targets = targets.toImmutableList(),
            confirm = step.target?.let { id -> targets.firstOrNull { it.speakerId == id } },
            sealed = if (state.input.draft) null else state.input.sealedVersions,
        )
    }

    /** The Save button (features.md section 4, item 8). */
    fun saveState(state: NamingState): SaveState {
        val trimmed = state.name.trim()
        val unchanged = trimmed == state.initialName.trim() &&
            state.boundVoiceId == state.initialBound &&
            effectiveRemember(state) == (state.initialRemember && rememberReason(state.input) == null) &&
            state.input.match?.state == Match.State.CONFIRMED
        return when {
            trimmed.isEmpty() || unchanged -> SaveState.DISABLED
            samePerson(state) != null || renameQuestion(state) != null -> SaveState.BLOCKED_BY_QUESTION
            else -> SaveState.ENABLED
        }
    }

    // Questions. ----------------------------------------------------------------------------------------------------

    /**
     * The same-person question (item 4): Remember is on, no voice is bound, a print exists, the name was not answered
     * "Someone else", and the typed name equals a saved voice's full name or first word. The best scored one.
     */
    fun samePerson(state: NamingState): Voice? {
        val input = state.input
        val typed = NameMatch.normalise(state.name)
        val open = effectiveRemember(state) && state.boundVoiceId == null && input.hasPrint
        val answerable = typed.isNotEmpty() && typed != state.declinedSamePerson
        return if (open && answerable) NameMatch.samePerson(state.name, input.voices, scores(input)) else null
    }

    /**
     * The rename-saved question (item 5): the match is CONFIRMED to voice V, the user edited the name, and it differs
     * from V's name, with no voice bound and no same-person question open.
     */
    fun renameQuestion(state: NamingState): Voice? {
        val confirmed = confirmedVoice(state.input) ?: return null
        val edited = !same(state.name, state.initialName)
        val open = state.renameAnswer == null && state.boundVoiceId == null && state.name.isNotBlank()
        return confirmed.takeIf { edited && open && !same(it.name, state.name) && samePerson(state) == null }
    }

    /** The voice of "Not <name>?": an AUTO or CONFIRMED match to a saved voice. */
    fun notName(input: NamingInput): Voice? {
        val match = input.match?.takeIf { it.state == Match.State.AUTO || it.state == Match.State.CONFIRMED }
        return match?.let { m -> input.voices.firstOrNull { it.id == m.voiceId } }
    }

    /** Why Remember is disabled, or null when it can be on. */
    fun rememberReason(input: NamingInput): RememberReason? {
        val speech = if (input.draft) input.speakingMs else (input.printSeconds * MS_PER_SECOND).toLong()
        return when {
            !input.voiceModelReady -> RememberReason.NO_MODEL
            !input.hasPrint || speech < MIN_REMEMBER_MS -> RememberReason.TOO_LITTLE_SPEECH
            else -> null
        }
    }

    // Event handlers. -----------------------------------------------------------------------------------------------

    private fun nameChanged(state: NamingState, text: String): NamingState {
        val name = text.take(MAX_NAME)
        val bound = state.boundVoiceId?.takeIf { id -> state.input.voices.any { it.id == id && same(it.name, name) } }
        val answer = state.renameAnswer.takeIf { same(name, state.name) }
        return state.copy(name = name, boundVoiceId = bound, renameAnswer = answer)
    }

    private fun pick(state: NamingState, voiceId: String): NamingState {
        val voice = state.input.voices.firstOrNull { it.id == voiceId } ?: return state
        return state.copy(name = voice.name.take(MAX_NAME), boundVoiceId = voice.id, renameAnswer = null)
    }

    private fun notThisPerson(state: NamingState): NamingResult {
        val input = state.input
        val voice = notName(input) ?: return NamingResult(state)
        val reject = NamingEffect.Reject(
            speakerId = input.speakerId,
            voiceId = voice.id,
            voiceName = voice.name,
            unenrol = input.match?.state == Match.State.CONFIRMED,
            restorePlaceholder = input.namedLabel?.let { same(it, voice.name) } == true,
            draft = input.draft,
        )
        return NamingResult(null, listOf(reject, NamingEffect.Close))
    }

    private fun playSample(state: NamingState): List<NamingEffect> {
        val sample = state.input.sample?.takeIf { state.input.canSample } ?: return emptyList()
        return listOf(NamingEffect.PlayRange(sample.startMs, sample.endMs))
    }

    private fun mergeTarget(state: NamingState, speakerId: String): NamingState =
        if (state.merge != null && state.input.mergeTargets.any { it.speakerId == speakerId }) {
            state.copy(merge = MergeStep(speakerId))
        } else {
            state
        }

    private fun confirmMerge(state: NamingState): NamingResult {
        val target = state.merge?.target?.let { id -> state.input.mergeTargets.firstOrNull { it.speakerId == id } }
            ?: return NamingResult(state)
        val source = state.input.speakerId
        val effect = if (state.input.draft) {
            NamingEffect.MergeDraft(source, target.speakerId, (target.label as? SpeakerNameUi.Named)?.text)
        } else {
            NamingEffect.MergeSealed(source, target.speakerId)
        }
        return NamingResult(null, listOf(effect, NamingEffect.Close))
    }

    private fun save(state: NamingState): NamingResult = when (saveState(state)) {
        SaveState.DISABLED -> NamingResult(state)
        SaveState.BLOCKED_BY_QUESTION -> NamingResult(state, listOf(NamingEffect.FocusQuestion))
        SaveState.ENABLED -> {
            val effects = if (state.input.draft) draftSave(state) else sealedSave(state)
            NamingResult(null, effects + NamingEffect.Close)
        }
    }

    /** 0.1.0 `rememberVoice` after the relabel (features.md section 4, sealed save). */
    private fun sealedSave(state: NamingState): List<NamingEffect> {
        val input = state.input
        val name = state.name.trim()
        val effects = mutableListOf<NamingEffect>()
        if (name != input.namedLabel) effects += NamingEffect.Relabel(input.speakerId, name)
        val previous = input.match?.takeIf { it.state == Match.State.CONFIRMED }?.voiceId
        val target = target(state, previous, name)
        if (previous != null && previous != target) effects += NamingEffect.Unenrol(input.speakerId)
        rememberEffect(state, previous, target, name)?.let { effects += it }
        return effects
    }

    /**
     * The voice file part of a sealed save: rename the saved voice (the speaker stays CONFIRMED to it), enrol into
     * [target] or a new voice, or clear an AUTO match to a voice the speaker is no longer called.
     */
    private fun rememberEffect(state: NamingState, previous: String?, target: String?, name: String): NamingEffect? {
        val input = state.input
        val auto = input.match?.takeIf { it.state == Match.State.AUTO }
        val autoName = auto?.let { m -> input.voices.firstOrNull { it.id == m.voiceId }?.name }
        val staleAuto = auto != null && auto.voiceId != target && (autoName == null || !same(autoName, name))
        return when {
            effectiveRemember(state) && previous != null && previous == target -> {
                val saved = input.voices.firstOrNull { it.id == previous }
                val rename = state.renameAnswer == true && saved != null && saved.name != name
                if (rename) NamingEffect.RenameVoice(previous, name) else null
            }
            effectiveRemember(state) -> NamingEffect.RememberVoice(input.speakerId, target, name)
            staleAuto -> NamingEffect.ClearMatch(input.speakerId)
            else -> null
        }
    }

    /** `DraftNames.rename` / `remember` (or `confirm` for a bound voice), with the generation added by the caller. */
    private fun draftSave(state: NamingState): List<NamingEffect> {
        val input = state.input
        val name = state.name.trim()
        val previous = input.match?.takeIf { it.state == Match.State.CONFIRMED }?.voiceId
        val target = target(state, previous, name)
        val remember = effectiveRemember(state)
        val voiceId = if (remember) target ?: "" else null
        val score = target?.let { id -> scores(input)[id] ?: input.match?.takeIf { it.voiceId == id }?.score } ?: 1f
        val effects = mutableListOf<NamingEffect>(
            NamingEffect.DraftName(input.speakerId, name, voiceId, confirm = remember && target != null, score),
        )
        val saved = previous?.let { id -> input.voices.firstOrNull { it.id == id } }
        if (state.renameAnswer == true && saved != null && saved.name != name) {
            effects += NamingEffect.RenameVoice(saved.id, name)
        }
        return effects
    }

    /** The voice the speaker is saved as: the bound one, else the CONFIRMED one if the name still is its name. */
    private fun target(state: NamingState, previous: String?, name: String): String? {
        state.boundVoiceId?.let { return it }
        val saved = previous?.let { id -> state.input.voices.firstOrNull { it.id == id } }
        return previous?.takeIf { state.renameAnswer == true || (saved != null && same(saved.name, name)) }
    }

    // Helpers. ------------------------------------------------------------------------------------------------------

    private fun canMerge(state: NamingState): Boolean = state.input.mergeTargets.isNotEmpty()

    private fun rememberable(state: NamingState): Boolean =
        rememberReason(state.input) == null && !state.input.voicesReadOnly

    private fun effectiveRemember(state: NamingState): Boolean = state.remember && rememberable(state)

    private fun confirmedVoice(input: NamingInput): Voice? {
        val match = input.match?.takeIf { it.state == Match.State.CONFIRMED } ?: return null
        return input.voices.firstOrNull { it.id == match.voiceId }
    }

    private fun rankedVoices(input: NamingInput): List<NameMatch.Ranked> =
        NameMatch.rank(input.print, input.voices, suggestThreshold())

    /** The cosine score of each current voice against the print (none without a print). */
    private fun scores(input: NamingInput): Map<String, Float> {
        val print = input.print ?: return emptyMap()
        return input.voices.filter { !VoiceStaleness.isStale(it) && it.mean.size == print.size }
            .associate { it.id to Voiceprints.cosine(print, it.mean) }
    }

    private fun suggestThreshold(): Float =
        Voiceprints.THRESHOLDS[ModelCatalog.voiceprintModel.sha256]?.suggest ?: DEFAULT_SUGGEST

    private fun same(a: String, b: String): Boolean = NameMatch.normalise(a) == NameMatch.normalise(b)

    private const val MS_PER_SECOND = 1_000
    private const val DEFAULT_SUGGEST = 0.5f
}
