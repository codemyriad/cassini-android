@file:Suppress("FunctionNaming", "MagicNumber")

package io.codemyriad.cassini.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.components.HelperText
import io.codemyriad.cassini.ui.components.SectionHeader
import io.codemyriad.cassini.ui.components.SettingsRow
import io.codemyriad.cassini.ui.components.SettingsTrailing
import io.codemyriad.cassini.ui.theme.CassiniIcon
import io.codemyriad.cassini.ui.theme.CassiniTheme
import io.codemyriad.cassini.ui.theme.Glyph

/** D106 L-58/L-59 Backup settings: switches say what is backed up, no top banner. */
@Composable
internal fun BackupSection(
    state: BackupSettingsUi,
    onEvent: (SettingsEvent) -> Unit,
    onDetails: () -> Unit,
    exportEnabled: Boolean,
    onExport: () -> Unit,
) {
    val voicesAvailable = state.cloudAvailable || state.transferAvailable
    SettingsGroup(Modifier.padding(top = 8.dp)) {
        BackupSwitch(
            stringResource(R.string.ui_backup_cloud_title),
            stringResource(R.string.ui_backup_meetings_subtitle),
            state.cloudOn,
            !state.saving && state.cloudAvailable,
        ) { onEvent(SettingsEvent.Cloud(it)) }
    }
    if (!state.cloudOn) HelperText(stringResource(cloudBody(state)))
    SettingsGroup(Modifier.padding(top = 12.dp)) {
        BackupSwitch(
            stringResource(R.string.ui_settings_transfer_title),
            stringResource(transferBody(state)),
            state.transferOn,
            !state.saving && state.transferAvailable,
        ) { onEvent(SettingsEvent.Transfer(it)) }
    }
    SettingsGroup(Modifier.padding(top = 12.dp)) {
        BackupSwitch(
            stringResource(R.string.ui_backup_voices_title),
            stringResource(R.string.ui_backup_voices_description),
            voicesAvailable && state.voices,
            !state.saving && voicesAvailable,
        ) { onEvent(SettingsEvent.IncludeVoices(it)) }
    }
    if (!voicesAvailable || !state.voices) HelperText(stringResource(voicesBody(state, voicesAvailable)))
    BackupWarnings(state)
    SectionHeader(stringResource(R.string.ui_backup_more_detail))
    SettingsGroup {
        SettingsRow(
            stringResource(R.string.ui_backup_more_detail_row),
            trailing = SettingsTrailing.Chevron,
            onClick = onDetails,
        )
    }
    SectionHeader(stringResource(R.string.ui_backup_own_copy))
    SettingsGroup {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = exportEnabled, role = Role.Button, onClick = onExport)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CassiniIcon(Glyph.Import, 24.dp, 1.8f, CassiniTheme.colors.accentInk, null)
            Text(
                stringResource(R.string.ui_backup_export_all),
                Modifier.padding(start = 12.dp),
                style = CassiniTheme.type.body16,
                color = if (exportEnabled) CassiniTheme.colors.ink else CassiniTheme.colors.text3,
            )
        }
    }
    HelperText(stringResource(R.string.ui_backup_zip_subtitle))
}

@StringRes
private fun cloudBody(state: BackupSettingsUi): Int = when {
    !state.cloudAvailable -> R.string.ui_settings_transfer_unavailable
    state.cloud -> R.string.ui_backup_cloud_body_on
    else -> R.string.ui_backup_cloud_body_off
}

@StringRes
private fun transferBody(state: BackupSettingsUi): Int = when {
    !state.transferAvailable -> R.string.ui_settings_transfer_unavailable
    state.transfer -> R.string.ui_settings_transfer_body
    state.cloudOn -> R.string.ui_settings_transfer_body_off_backup_on
    else -> R.string.ui_settings_transfer_body_off
}

@StringRes
private fun voicesBody(state: BackupSettingsUi, available: Boolean): Int = when {
    !available -> R.string.ui_settings_transfer_unavailable
    state.voices -> R.string.ui_backup_voices_subtitle
    else -> R.string.ui_backup_voices_subtitle_off
}

@Composable
internal fun BackupWarnings(state: BackupSettingsUi) {
    if (state.cloudOn && state.cloudQuotaExceeded) HelperText(stringResource(R.string.ui_backup_info_over_quota))
    if (state.transferOn && state.quotaExceeded) HelperText(stringResource(R.string.ui_settings_transfer_too_large))
    if (state.error) HelperText(stringResource(R.string.ui_settings_transfer_choice_failed))
}

@Composable
internal fun BackupSwitch(
    title: String,
    body: String?,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val colors = CassiniTheme.colors
    Row(
        Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = if (body != null) Alignment.Top else Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = CassiniTheme.type.button, color = colors.ink)
            if (body != null) Text(body, style = CassiniTheme.type.caption, color = colors.text2)
        }
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}
