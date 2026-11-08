// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 8.
@file:Suppress("FunctionNaming", "MagicNumber")

package io.codemyriad.cassini.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.banner.BannerAction
import io.codemyriad.cassini.ui.banner.BannerKind
import io.codemyriad.cassini.ui.banner.BannerSpec
import io.codemyriad.cassini.ui.components.AboutCard
import io.codemyriad.cassini.ui.components.Banner
import io.codemyriad.cassini.ui.components.CassiniTopBar
import io.codemyriad.cassini.ui.components.GroupCard
import io.codemyriad.cassini.ui.components.HelperText
import io.codemyriad.cassini.ui.components.SectionHeader
import io.codemyriad.cassini.ui.components.SettingsRow
import io.codemyriad.cassini.ui.components.SettingsTrailing
import io.codemyriad.cassini.ui.components.screenContentWidth
import io.codemyriad.cassini.ui.format.Counts
import io.codemyriad.cassini.ui.format.ModelPartUi
import io.codemyriad.cassini.ui.format.Sizes
import io.codemyriad.cassini.ui.theme.CassiniTheme
import io.codemyriad.cassini.ui.theme.Tone

/** The notifications banner of Settings: NOTIFICATIONS_OFF with its Turn on. */
private val NotificationsBanner = BannerSpec(BannerKind.NOTIFICATIONS_OFF)

/**
 * Settings (board 08, features.md section 11; ui.md section 8). One scrolling Column on bg: the top bar ("Back to
 * library"); "App language" ([LanguageSection]); the notifications banner when
 * [SettingsUiState.notificationsOff]; "On-device models" with a row per part and the helper; "Privacy" with the
 * Saved voices and Trash rows; "About" with the version card and its "Source code and notices" link.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(CassiniTheme.colors.bg)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .verticalScroll(rememberScrollState())
            .screenContentWidth()
            .navigationBarsPadding()
            .padding(bottom = 24.dp),
    ) {
        CassiniTopBar(
            title = stringResource(R.string.ui_settings_title),
            onBack = { onEvent(SettingsEvent.Back) },
            backLabel = stringResource(R.string.ui_back_to_library),
        )
        LanguageSection(state.language, onEvent)
        if (state.notificationsOff) {
            Banner(
                spec = NotificationsBanner,
                onAction = { if (it == BannerAction.TURN_ON) onEvent(SettingsEvent.TurnOnNotifications) },
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
            )
        }
        SectionHeader(stringResource(R.string.ui_settings_models))
        SpeechModels(state, onEvent)
        GroupCard(Modifier.padding(horizontal = 16.dp)) {
            state.models.filterNot { it.part == ModelPartUi.SPEECH }.forEach { row ->
                ModelRow(row, onClick = { onEvent(SettingsEvent.OpenModel(row.part)) })
            }
        }
        HelperText(stringResource(R.string.ui_settings_models_footnote))
        PrivacySection(state, onEvent)
        SectionHeader(stringResource(R.string.ui_settings_about))
        AboutCard(
            version = state.version,
            onSourceClick = { onEvent(SettingsEvent.OpenLicenses) },
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

/** Model choice applies to new jobs; existing queue entries keep their pinned recognizer. */
@Composable
private fun SpeechModels(state: SettingsUiState, onEvent: (SettingsEvent) -> Unit) {
    val included = state.speechModel == SpeechModelUi.INCLUDED && !state.speechChecking
    val (status, tone) = when {
        state.speechChecking -> stringResource(R.string.ui_settings_model_checking) to Tone.Info
        !state.includedAvailable -> stringResource(R.string.ui_settings_unavailable) to Tone.Plain
        included -> stringResource(R.string.ui_settings_default) to Tone.Ok
        else -> stringResource(R.string.ui_settings_model_ready) to Tone.Ok
    }
    GroupCard(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
        SettingsRow(
            title = "Whistle",
            subtitle = stringResource(if (state.includedAvailable) R.string.ui_settings_included else R.string.ui_settings_not_available),
            trailing = SettingsTrailing.Status(status, tone),
            modifier = Modifier.semantics { selected = included },
            onClick = if (state.includedAvailable) ({ onEvent(SettingsEvent.ChooseSpeech(SpeechModelUi.INCLUDED)) }) else null,
        )
        val parakeet = state.models.firstOrNull { it.part == ModelPartUi.SPEECH }
        val selected = state.speechModel == SpeechModelUi.PARAKEET && !state.speechChecking
        val ready = parakeet?.status == ModelStatusUi.Ready
        val (label, modelTone) = if (ready && selected) {
            stringResource(R.string.ui_settings_default) to Tone.Ok
        } else {
            modelStatus(parakeet?.status ?: ModelStatusUi.Checking)
        }
        SettingsRow(
            title = "Parakeet Ultra",
            subtitle = if (parakeet == null) {
                stringResource(R.string.ui_settings_optional)
            } else {
                stringResource(R.string.ui_settings_optional_size, Sizes.megabytes(parakeet.bytes))
            },
            trailing = SettingsTrailing.Status(label, modelTone),
            modifier = Modifier.semantics { this.selected = selected },
            onClick = { onEvent(SettingsEvent.ChooseSpeech(SpeechModelUi.PARAKEET)) },
        )
    }
    HelperText(stringResource(R.string.ui_settings_model_choice_footnote))
}

/**
 * "App language": a row that names the [current] choice and opens the [LanguageSheet], then the helper. The sheet's
 * state is saved, and the sheet closes before a pick is sent, because the new language creates the activity again.
 */
@Composable
private fun LanguageSection(current: LanguageChoice, onEvent: (SettingsEvent) -> Unit) {
    var sheet by rememberSaveable { mutableStateOf(false) }
    if (sheet) {
        LanguageSheet(
            current = current,
            onPick = { choice ->
                sheet = false
                onEvent(SettingsEvent.Language(choice))
            },
            onDismiss = { sheet = false },
        )
    }
    SectionHeader(stringResource(R.string.ui_settings_language), first = true)
    GroupCard(Modifier.padding(horizontal = 16.dp)) {
        SettingsRow(title = languageName(current), trailing = SettingsTrailing.Chevron, onClick = { sheet = true })
    }
    HelperText(stringResource(R.string.ui_settings_language_footnote), normalLineHeight = true)
}

/** "Privacy": the Saved voices and Trash rows with their counts ("None yet" and "Empty" at 0). */
@Composable
private fun PrivacySection(state: SettingsUiState, onEvent: (SettingsEvent) -> Unit) {
    SectionHeader(stringResource(R.string.ui_settings_privacy))
    GroupCard(Modifier.padding(horizontal = 16.dp)) {
        SettingsRow(
            title = stringResource(R.string.ui_settings_voices),
            subtitle = if (state.voices == 0) {
                stringResource(R.string.ui_settings_voices_none)
            } else {
                Counts.plural(R.plurals.ui_count_people, state.voices)
            },
            trailing = SettingsTrailing.Chevron,
            onClick = { onEvent(SettingsEvent.OpenVoices) },
        )
        SettingsRow(
            title = stringResource(R.string.ui_settings_trash),
            subtitle = if (state.trash == 0) {
                stringResource(R.string.ui_settings_trash_empty)
            } else {
                Counts.plural(R.plurals.ui_count_notes, state.trash)
            },
            trailing = SettingsTrailing.Chevron,
            onClick = { onEvent(SettingsEvent.OpenTrash) },
        )
    }
}

/**
 * An On-device models row: the part, its size ("INT8 · 640 MB" for speech recognition) and the status in its tone:
 * Ready teal, Downloading and Checking blue, Pending amber, Failed red, Not downloaded plain. A Not downloaded row
 * and a Failed row are buttons; they open Model download.
 */
@Composable
private fun ModelRow(row: ModelRowUi, onClick: () -> Unit) {
    val size = Sizes.megabytes(row.bytes)
    val (text, tone) = modelStatus(row.status)
    SettingsRow(
        title = row.part.label(),
        subtitle = if (row.part == ModelPartUi.SPEECH) stringResource(R.string.ui_models_int8_size, size) else size,
        trailing = SettingsTrailing.Status(text, tone),
        onClick = onClick.takeIf { row.status == ModelStatusUi.NotDownloaded || row.status == ModelStatusUi.Failed },
    )
}

@Composable
private fun modelStatus(status: ModelStatusUi): Pair<String, Tone> = when (status) {
    ModelStatusUi.Ready -> stringResource(R.string.ui_settings_model_ready) to Tone.Ok
    is ModelStatusUi.Downloading -> stringResource(R.string.ui_settings_model_downloading, status.percent) to
        Tone.Info
    ModelStatusUi.Checking -> stringResource(R.string.ui_settings_model_checking) to Tone.Info
    ModelStatusUi.Pending -> stringResource(R.string.ui_settings_model_pending) to Tone.Warn
    ModelStatusUi.NotDownloaded -> stringResource(R.string.ui_settings_model_not_downloaded) to Tone.Plain
    ModelStatusUi.Failed -> stringResource(R.string.ui_settings_model_failed) to Tone.Error
}
