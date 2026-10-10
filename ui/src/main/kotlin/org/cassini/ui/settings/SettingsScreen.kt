// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 8.
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.settings

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.persistentListOf
import org.cassini.ui.R
import org.cassini.ui.banner.BannerAction
import org.cassini.ui.banner.BannerKind
import org.cassini.ui.banner.BannerSpec
import org.cassini.ui.components.AboutCard
import org.cassini.ui.components.Banner
import org.cassini.ui.components.CassiniTopBar
import org.cassini.ui.components.GroupCard
import org.cassini.ui.components.HelperText
import org.cassini.ui.components.SectionHeader
import org.cassini.ui.components.SegmentedControl
import org.cassini.ui.components.SettingsRow
import org.cassini.ui.components.SettingsTrailing
import org.cassini.ui.components.screenContentWidth
import org.cassini.ui.format.Counts
import org.cassini.ui.format.ModelPartUi
import org.cassini.ui.format.Sizes
import org.cassini.ui.theme.CassiniTheme
import org.cassini.ui.theme.Tone

/** The notifications banner of Settings: NOTIFICATIONS_OFF with its Turn on. */
private val NotificationsBanner = BannerSpec(BannerKind.NOTIFICATIONS_OFF)

/**
 * Settings (board 08, features.md section 11; ui.md section 8). One scrolling Column on bg: the top bar ("Back to
 * library"); "App language" with the segmented control and its helper; the notifications banner when
 * [SettingsUiState.notificationsOff]; "On-device models" with a row per part and the helper; "Privacy" with the
 * Saved voices and Trash rows; "About" with the version card and its "Source code and notices" link.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val languages = persistentListOf(
        stringResource(R.string.ui_settings_language_phone),
        stringResource(R.string.ui_language_english),
        stringResource(R.string.ui_language_italian),
    )
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
        SectionHeader(stringResource(R.string.ui_settings_language), first = true)
        SegmentedControl(
            options = languages,
            selectedIndex = state.language.ordinal,
            onSelect = { onEvent(SettingsEvent.Language(LanguageChoice.entries[it])) },
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        HelperText(stringResource(R.string.ui_settings_language_footnote), normalLineHeight = true)
        if (state.notificationsOff) {
            Banner(
                spec = NotificationsBanner,
                onAction = { if (it == BannerAction.TURN_ON) onEvent(SettingsEvent.TurnOnNotifications) },
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
            )
        }
        SectionHeader(stringResource(R.string.ui_settings_models))
        GroupCard(Modifier.padding(horizontal = 16.dp)) {
            state.models.forEach { row ->
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
 * Ready teal, Downloading and Checking blue, Pending amber, Failed red, Not downloaded plain. Only a Not downloaded
 * row is a button; it opens Model download.
 */
@Composable
private fun ModelRow(row: ModelRowUi, onClick: () -> Unit) {
    val size = Sizes.megabytes(row.bytes)
    val (text, tone) = when (val status = row.status) {
        ModelStatusUi.Ready -> stringResource(R.string.ui_settings_model_ready) to Tone.Ok
        is ModelStatusUi.Downloading -> stringResource(R.string.ui_settings_model_downloading, status.percent) to
            Tone.Info
        ModelStatusUi.Checking -> stringResource(R.string.ui_settings_model_checking) to Tone.Info
        ModelStatusUi.Pending -> stringResource(R.string.ui_settings_model_pending) to Tone.Warn
        ModelStatusUi.NotDownloaded -> stringResource(R.string.ui_settings_model_not_downloaded) to Tone.Plain
        ModelStatusUi.Failed -> stringResource(R.string.ui_settings_model_failed) to Tone.Error
    }
    SettingsRow(
        title = row.part.label(),
        subtitle = if (row.part == ModelPartUi.SPEECH) stringResource(R.string.ui_models_int8_size, size) else size,
        trailing = SettingsTrailing.Status(text, tone),
        onClick = if (row.status == ModelStatusUi.NotDownloaded) onClick else null,
    )
}
