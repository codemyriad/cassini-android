package io.codemyriad.cassini.note

import io.codemyriad.cassini.core.speakers.Match
import io.codemyriad.cassini.ui.components.SnackbarKind
import io.codemyriad.cassini.ui.components.SpeakerNameUi

/**
 * What one Save of the Name sheet changed, so that Undo on its snackbar puts everything back (cassini-ux D112 /
 * L-86): the stored [labels] and [background] states of the speakers it changed (one document write), the speakers
 * it [enrolled] into a saved voice, the [reenrol]ments it took back, the earlier [matches] and the earlier names of
 * the [renamedVoices]. [summary] is the snackbar, null when the Save switched no background voice on (the catalogue
 * has no wording for a rename alone, so a rename keeps showing no snackbar, as before D112).
 */
data class SpeakerUndo(
    val labels: Map<String, String>,
    val background: Map<String, Boolean>,
    val enrolled: List<String>,
    val reenrol: Map<String, Pair<String, String>>,
    val matches: Map<String, Match?>,
    val renamedVoices: Map<String, String>,
    val summary: Summary?,
) {
    /** The snackbar text: [kind] with the speaker's [name] and the [count] of new background voices. */
    data class Summary(val kind: SnackbarKind, val name: String?, val count: Int)

    companion object {
        /**
         * The undo of [effects], the effects of saving [session]. [display] is the name a speaker shows with (a
         * placeholder in the UI language when the file stores no label).
         */
        fun of(session: NamingSession, effects: List<NamingEffect>, display: (SpeakerNameUi) -> String): SpeakerUndo {
            val inputs = session.forms.mapValues { it.value.input }
            val relabels = effects.filterIsInstance<NamingEffect.Relabel>()
            val switches = effects.filterIsInstance<NamingEffect.Background>()
            val labels = relabels.mapNotNull { effect ->
                inputs[effect.speakerId]?.let { effect.speakerId to storedLabel(it) }
            }.toMap()
            val background = switches.mapNotNull { effect ->
                inputs[effect.speakerId]?.let { effect.speakerId to it.background }
            }.toMap()
            val enrolled = effects.filterIsInstance<NamingEffect.RememberVoice>().map { it.speakerId }
            val reenrol = effects.filterIsInstance<NamingEffect.Unenrol>().mapNotNull { effect ->
                val input = inputs[effect.speakerId] ?: return@mapNotNull null
                val voice = input.match?.voiceId ?: return@mapNotNull null
                val name = input.voices.firstOrNull { it.id == voice }?.name ?: return@mapNotNull null
                effect.speakerId to (voice to name)
            }.toMap()
            val cleared = effects.filterIsInstance<NamingEffect.ClearMatch>().map { it.speakerId }
            val matches = (enrolled + cleared).filter { it !in reenrol }.associateWith { inputs[it]?.match }
            val renamed = effects.filterIsInstance<NamingEffect.RenameVoice>().mapNotNull { effect ->
                inputs.values.flatMap { it.voices }.firstOrNull { it.id == effect.voiceId }?.let { effect.voiceId to it.name }
            }.toMap()
            val summary = summary(switches, relabels, inputs, display)
            return SpeakerUndo(labels, background, enrolled, reenrol, matches, renamed, summary)
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
