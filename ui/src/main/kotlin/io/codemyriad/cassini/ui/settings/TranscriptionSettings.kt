@file:Suppress("FunctionNaming", "MagicNumber")

package io.codemyriad.cassini.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.banner.BannerAction
import io.codemyriad.cassini.ui.banner.BannerKind
import io.codemyriad.cassini.ui.banner.BannerSpec
import io.codemyriad.cassini.ui.components.Banner
import io.codemyriad.cassini.ui.components.HelperText
import io.codemyriad.cassini.ui.components.Radio
import io.codemyriad.cassini.ui.components.SecondaryButton
import io.codemyriad.cassini.ui.components.SectionHeader
import io.codemyriad.cassini.ui.components.SettingsRow
import io.codemyriad.cassini.ui.components.SettingsTrailing
import io.codemyriad.cassini.ui.format.ModelPartUi
import io.codemyriad.cassini.ui.format.Sizes
import io.codemyriad.cassini.ui.theme.CassiniTheme

@Composable
internal fun TranscriptionSettings(state: SettingsUiState, onEvent: (SettingsEvent) -> Unit) {
    SectionHeader(stringResource(R.string.ui_transcription_for_new), first = true)
    ParakeetNotices(state, onEvent)
    SettingsGroup(Modifier.selectableGroup()) {
        SpeechChoiceRow(state, SpeechModelUi.INCLUDED, onEvent)
        SpeechChoiceRow(state, SpeechModelUi.PARAKEET, onEvent)
    }
    HelperText(stringResource(R.string.ui_models_keep_transcript))
    SectionHeader(stringResource(R.string.ui_transcription_after_recording), topPadding = 30.dp)
    SettingsGroup {
        BackupSwitch(
            stringResource(R.string.ui_transcription_right_away),
            body = null,
            checked = state.transcribeRightAway,
            enabled = true,
        ) { onEvent(SettingsEvent.TranscribeRightAway(it)) }
    }
    HelperText(stringResource(R.string.ui_transcription_right_away_subtitle))
    SectionHeader(stringResource(R.string.ui_transcription_online_heading), topPadding = 30.dp)
    SettingsGroup {
        SettingsRow(
            stringResource(R.string.ui_models_where_from),
            trailing = SettingsTrailing.Chevron,
            onClick = { onEvent(SettingsEvent.OpenSources) },
        )
    }
    HelperText(stringResource(R.string.ui_transcription_online))
}

@Composable
@Suppress("CyclomaticComplexMethod") // The two model choices share availability and install states.
private fun SpeechChoiceRow(state: SettingsUiState, model: SpeechModelUi, onEvent: (SettingsEvent) -> Unit) {
    val included = model == SpeechModelUi.INCLUDED
    val part = state.models.firstOrNull { it.part == ModelPartUi.SPEECH }
    val ready = if (included) state.includedAvailable else part?.status == ModelStatusUi.Ready
    val enabled = !state.speechChecking && if (included) ready else part?.status?.isInstalling != true
    val selected = state.speechModel == model && !state.speechChecking
    val choose = { onEvent(SettingsEvent.ChooseSpeech(model)) }
    Row(
        Modifier.fillMaxWidth()
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = choose)
            .padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Radio(selected)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                stringResource(if (included) R.string.ui_model_whistle else R.string.ui_model_parakeet),
                style = CassiniTheme.type.button,
                color = CassiniTheme.colors.ink,
            )
            Text(
                stringResource(if (included) R.string.ui_model_whistle_short else R.string.ui_model_parakeet_short),
                style = CassiniTheme.type.caption,
                color = CassiniTheme.colors.text2,
            )
        }
        Column(Modifier.weight(0.6f).padding(start = 12.dp), horizontalAlignment = Alignment.End) {
            val status = when {
                state.speechChecking -> stringResource(R.string.ui_settings_model_checking)
                included && ready -> stringResource(R.string.ui_model_built_in)
                included -> stringResource(R.string.ui_settings_unavailable)
                else -> modelStatusLabel(part?.status ?: ModelStatusUi.Checking)
            }
            if (!included && part?.status?.canOpenDownload == true) {
                SecondaryButton(stringResource(R.string.ui_model_download), choose, enabled = enabled)
            } else {
                Text(status, style = CassiniTheme.type.caption, color = CassiniTheme.colors.text2)
            }
            if (!included && part != null) {
                Text(Sizes.megabytes(part.bytes), style = CassiniTheme.type.caption, color = CassiniTheme.colors.text2)
            }
        }
    }
}

@Composable
internal fun modelStatusLabel(status: ModelStatusUi): String = when (status) {
    ModelStatusUi.Ready -> stringResource(R.string.ui_settings_model_ready)
    is ModelStatusUi.Downloading -> stringResource(R.string.ui_settings_model_downloading, status.percent)
    ModelStatusUi.Checking -> stringResource(R.string.ui_settings_model_checking)
    ModelStatusUi.Pending -> stringResource(R.string.ui_settings_model_pending)
    ModelStatusUi.NotDownloaded -> stringResource(R.string.ui_settings_model_not_downloaded)
    ModelStatusUi.Failed -> stringResource(R.string.ui_settings_model_failed)
}

@Composable
private fun ParakeetNotices(state: SettingsUiState, onEvent: (SettingsEvent) -> Unit) {
    if (state.parakeetNowDefault) {
        Banner(
            BannerSpec(BannerKind.PARAKEET_NOW_DEFAULT),
            onAction = {},
            onDismiss = { onEvent(SettingsEvent.DismissParakeetDefault) },
            modifier = Modifier.padding(16.dp),
        )
    }
    state.parakeetSuggestion?.let { suggestion ->
        Banner(
            suggestion,
            onAction = {
                onEvent(
                    if (it == BannerAction.DOWNLOAD) SettingsEvent.DownloadParakeet else SettingsEvent.DismissParakeet,
                )
            },
            onDismiss = { onEvent(SettingsEvent.DismissParakeet) },
            modifier = Modifier.padding(16.dp),
        )
    }
}
