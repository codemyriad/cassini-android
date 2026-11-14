@file:Suppress("FunctionNaming", "MagicNumber")

package io.codemyriad.cassini.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.components.CassiniDialog
import io.codemyriad.cassini.ui.components.GroupCard
import io.codemyriad.cassini.ui.components.HelperText
import io.codemyriad.cassini.ui.components.SectionHeader
import io.codemyriad.cassini.ui.components.SettingsRow
import io.codemyriad.cassini.ui.components.SettingsTrailing
import io.codemyriad.cassini.ui.components.TextButton
import io.codemyriad.cassini.ui.theme.CassiniTheme

/**
 * Moving and backup (PARITY Wave 6 contract §3, D98): layer 1 is three independent switches, "Encrypted cloud
 * backup", "Move to a new phone" and "Include saved voices in backups", whose copy follows the actual state (the
 * Android table); "Learn more" opens layer 2. Entire-row switch semantics, one node per choice; a failed write never
 * optimistically changes a switch.
 */
@Composable
internal fun BackupSection(state: BackupSettingsUi, onEvent: (SettingsEvent) -> Unit) {
    var info by rememberSaveable { mutableStateOf(false) }
    val voicesAvailable = state.cloudAvailable || state.transferAvailable
    SectionHeader(stringResource(R.string.ui_backup_heading))
    GroupCard(Modifier.padding(horizontal = 16.dp)) {
        BackupSwitch(
            stringResource(R.string.ui_backup_cloud_title),
            stringResource(cloudBody(state)),
            state.cloudOn,
            !state.saving && state.cloudAvailable,
        ) { onEvent(SettingsEvent.Cloud(it)) }
        BackupSwitch(
            stringResource(R.string.ui_settings_transfer_title),
            stringResource(transferBody(state)),
            state.transferOn,
            !state.saving && state.transferAvailable,
        ) { onEvent(SettingsEvent.Transfer(it)) }
        BackupSwitch(
            stringResource(R.string.ui_backup_voices_title),
            stringResource(voicesBody(state, voicesAvailable)),
            voicesAvailable && state.voices,
            !state.saving && voicesAvailable,
        ) { onEvent(SettingsEvent.IncludeVoices(it)) }
        SettingsRow(
            stringResource(R.string.ui_backup_learn_more),
            trailing = SettingsTrailing.Chevron,
            onClick = { info = true },
        )
    }
    for (helper in helpers(state)) HelperText(stringResource(helper))
    if (info) BackupInfoDialog { info = false }
}

@StringRes
private fun cloudBody(state: BackupSettingsUi): Int = when {
    !state.cloudAvailable -> R.string.ui_settings_transfer_unavailable
    state.cloud -> R.string.ui_backup_cloud_body_on
    else -> R.string.ui_backup_cloud_body_off
}

/** With cloud backup on, "off" says that meetings still come back from the cloud backup (the Android table). */
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

/** The lines under the group: phone-only, the ZIP hint, each channel's size refusal and a failed write. */
private fun helpers(state: BackupSettingsUi): List<Int> = buildList {
    if (state.phoneOnly) add(R.string.ui_backup_phone_only_body)
    add(R.string.ui_settings_transfer_export_hint)
    if (state.cloudOn && state.cloudQuotaExceeded) add(R.string.ui_backup_info_over_quota)
    if (state.transferOn && state.quotaExceeded) add(R.string.ui_settings_transfer_too_large)
    if (state.error) add(R.string.ui_settings_transfer_choice_failed)
}

/** Layer 2: what is backed up, who can read it and a copy you keep yourself (PARITY Wave 6 contract §3). */
@Composable
private fun BackupInfoDialog(onDismiss: () -> Unit) {
    CassiniDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.ui_backup_info_title),
        actions = { TextButton(stringResource(R.string.ui_action_close), onClick = onDismiss) },
        content = {
            InfoBlock(R.string.ui_backup_info_what_heading, R.string.ui_backup_info_what_body)
            InfoBlock(R.string.ui_backup_info_android_heading, R.string.ui_backup_info_android_body)
            InfoBlock(R.string.ui_backup_info_manual_heading, R.string.ui_settings_transfer_export_hint)
        },
    )
}

@Composable
private fun InfoBlock(heading: Int, body: Int) {
    val colors = CassiniTheme.colors
    Column(Modifier.padding(bottom = 12.dp)) {
        Text(
            stringResource(heading),
            Modifier.semantics { heading() },
            style = CassiniTheme.type.button,
            color = colors.ink,
        )
        Text(stringResource(body), style = CassiniTheme.type.body14, color = colors.text2)
    }
}

@Composable
private fun BackupSwitch(title: String, body: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val colors = CassiniTheme.colors
    Row(
        Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = CassiniTheme.type.button, color = colors.ink)
            Text(body, style = CassiniTheme.type.caption, color = colors.text2)
        }
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}
