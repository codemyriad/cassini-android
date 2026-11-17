@file:Suppress("FunctionNaming", "MagicNumber")

package io.codemyriad.cassini.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.components.HelperText
import io.codemyriad.cassini.ui.components.SectionHeader
import io.codemyriad.cassini.ui.components.SettingsRow
import io.codemyriad.cassini.ui.components.SettingsTrailing
import io.codemyriad.cassini.ui.format.Counts
import io.codemyriad.cassini.ui.format.ModelPartUi
import io.codemyriad.cassini.ui.format.Sizes
import io.codemyriad.cassini.ui.theme.Glyph
import io.codemyriad.cassini.ui.theme.Tone

@Composable
internal fun SpeakerSettings(state: SettingsUiState, onEvent: (SettingsEvent) -> Unit) {
    SectionHeader(stringResource(R.string.ui_speakers_who), first = true)
    SettingsGroup {
        BackupSwitch(
            stringResource(R.string.ui_speakers_tell_apart),
            stringResource(R.string.ui_speakers_tell_apart_short),
            state.tellSpeakersApart,
            !state.speakerSaving,
        ) { onEvent(SettingsEvent.SpeakerChoice(ModelPartUi.SPEAKERS, it)) }
        BackupSwitch(
            stringResource(R.string.ui_speakers_recognise),
            stringResource(R.string.ui_speakers_recognise_short),
            state.recogniseVoices,
            !state.speakerSaving,
        ) { onEvent(SettingsEvent.SpeakerChoice(ModelPartUi.VOICES, it)) }
    }
    HelperText(stringResource(R.string.ui_speakers_on_device))
    if (state.speakerError) HelperText(stringResource(R.string.ui_settings_transfer_choice_failed))
    SectionHeader(stringResource(R.string.ui_settings_voices))
    SettingsGroup {
        // D103 (L-60): the default of "Remember this voice"; the voices question sets it first.
        BackupSwitch(
            stringResource(R.string.ui_speakers_remember),
            stringResource(R.string.ui_speakers_remember_short),
            state.rememberVoices,
            !state.speakerSaving,
        ) { onEvent(SettingsEvent.RememberVoices(it)) }
        MapRow(
            stringResource(R.string.ui_settings_voices),
            Counts.plural(R.plurals.ui_settings_people, state.voices),
            Glyph.People,
        ) {
            onEvent(SettingsEvent.OpenVoices)
        }
    }
    HelperText(stringResource(R.string.ui_voices_privacy_phone))
    SectionHeader(stringResource(R.string.ui_settings_models))
    SettingsGroup {
        state.models.filterNot { it.part == ModelPartUi.SPEECH }.forEach { row ->
            SettingsRow(
                row.part.label(),
                subtitle = Sizes.megabytes(row.bytes),
                trailing = SettingsTrailing.Status(modelStatusLabel(row.status), Tone.Plain),
                onClick = { onEvent(SettingsEvent.OpenModel(row.part)) }.takeIf { row.status.canOpenDownload },
            )
        }
        SettingsRow(
            stringResource(R.string.ui_models_where_from),
            trailing = SettingsTrailing.Chevron,
            onClick = { onEvent(SettingsEvent.OpenSources) },
        )
    }
}
