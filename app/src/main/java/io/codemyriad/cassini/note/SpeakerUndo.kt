package io.codemyriad.cassini.note

import io.codemyriad.cassini.core.speakers.Match
import io.codemyriad.cassini.data.voices.UnenrolRollback
import io.codemyriad.cassini.ui.components.SnackbarKind
import io.codemyriad.cassini.ui.components.SpeakerNameUi

/**
 * One inverse voice operation recorded as a Save's effects run, so Undo can replay them in strict reverse order
 * (`asReversed()`) and each step sees the exact state that existed immediately after its forward step ran.
 */
sealed interface VoiceUndoOp {
    /** Takes back an enrolment added by Save and puts back the [previousMatch] that existed right before it. */
    data class Unenrol(val speakerId: String, val previousMatch: Match?) : VoiceUndoOp

    /** Restores a confirmed enrolment taken back by Save via its captured [rollback] snapshot. */
    data class RestoreUnenrol(val rollback: UnenrolRollback) : VoiceUndoOp

    /** Restores a match cleared by Save (`ClearMatch`). */
    data class RestoreMatch(val speakerId: String, val match: Match) : VoiceUndoOp

    /** Restores the earlier [previousName] of a voice renamed by Save. */
    data class RenameVoice(val voiceId: String, val previousName: String) : VoiceUndoOp
}

/**
 * What one Save of the Name sheet changed, so that Undo on its snackbar puts everything back (cassini-ux D112 /
 * L-86): the [variantId] that was edited, the stored [labels] and [background] states of the speakers it changed (one
 * document write), and the ordered [voiceOps] inverse voice operations recorded in effect order (replayed in strict
 * reverse order on Undo). [summary] is the snackbar, null when the Save switched no background voice on (the catalogue
 * has no wording for a rename alone, so a rename keeps showing no snackbar, as before D112).
 */
data class SpeakerUndo(
    val variantId: String?,
    val labels: Map<String, String>,
    val background: Map<String, Boolean>,
    val voiceOps: List<VoiceUndoOp>,
    val summary: Summary?,
) {
    /** The snackbar text: [kind] with the speaker's [name] and the [count] of new background voices. */
    data class Summary(val kind: SnackbarKind, val name: String?, val count: Int)

    /**
     * Records inverse voice operations in effect order as Save carries out its effects, tracking the per-speaker match
     * and voice names across steps so each inverse op sees the exact state right before its forward step ran.
     */
    class Recorder(session: NamingSession) {
        private val matches = session.forms.mapValuesTo(mutableMapOf()) { it.value.input.match }
        private val voiceNames = session.forms.values
            .flatMap { it.input.voices }
            .associateTo(mutableMapOf()) { it.id to it.name }
        private val ops = mutableListOf<VoiceUndoOp>()

        fun onUnenrolled(speakerId: String, rollback: UnenrolRollback) {
            ops += VoiceUndoOp.RestoreUnenrol(rollback)
            matches[speakerId] = null
        }

        fun onEnrolled(speakerId: String) {
            ops += VoiceUndoOp.Unenrol(speakerId, matches[speakerId])
            matches[speakerId] = null
        }

        fun onClearedMatch(speakerId: String) {
            matches[speakerId]?.let { ops += VoiceUndoOp.RestoreMatch(speakerId, it) }
            matches[speakerId] = null
        }

        fun onRenamedVoice(voiceId: String, newName: String) {
            voiceNames[voiceId]?.let { ops += VoiceUndoOp.RenameVoice(voiceId, it) }
            voiceNames[voiceId] = newName
        }

        fun ops(): List<VoiceUndoOp> = ops.toList()
    }

    companion object {
        /**
         * The undo of [effects], the effects of saving [session] on [variantId]. [display] is the name a speaker shows
         * with (a placeholder in the UI language when the file stores no label).
         */
        fun of(
            session: NamingSession,
            effects: List<NamingEffect>,
            variantId: String? = null,
            display: (SpeakerNameUi) -> String,
        ): SpeakerUndo {
            val inputs = session.forms.mapValues { it.value.input }
            val relabels = effects.filterIsInstance<NamingEffect.Relabel>()
            val switches = effects.filterIsInstance<NamingEffect.Background>()
            val labels = relabels.mapNotNull { effect ->
                inputs[effect.speakerId]?.let { effect.speakerId to storedLabel(it) }
            }.toMap()
            val background = switches.mapNotNull { effect ->
                inputs[effect.speakerId]?.let { effect.speakerId to it.background }
            }.toMap()
            val summary = summary(switches, relabels, inputs, display)
            return SpeakerUndo(variantId, labels, background, emptyList(), summary)
        }

        private fun summary(
            switches: List<NamingEffect.Background>,
            relabels: List<NamingEffect.Relabel>,
            inputs: Map<String, NamingInput>,
            display: (SpeakerNameUi) -> String,
        ): Summary? {
            val on = switches.filter { it.background }
            return when {
                on.isEmpty() -> null
                relabels.isNotEmpty() -> Summary(SnackbarKind.NAMED_AND_BACKGROUND, relabels.first().label, on.size)
                on.size == 1 -> Summary(
                    SnackbarKind.BACKGROUND_VOICE,
                    inputs[on.single().speakerId]?.label?.let(display),
                    1,
                )
                else -> Summary(SnackbarKind.BACKGROUND_VOICES, null, on.size)
            }
        }

        /** The label the file stores for the speaker of [input]; a file without one stores the speaker id. */
        private fun storedLabel(input: NamingInput): String = when (val label = input.label) {
            is SpeakerNameUi.Named -> label.text
            is SpeakerNameUi.Placeholder -> label.stored ?: input.speakerId
        }
    }
}
