@file:Suppress("FunctionNaming", "MagicNumber")

package io.codemyriad.cassini.ui.settings

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
import io.codemyriad.cassini.ui.components.GroupCard
import io.codemyriad.cassini.ui.components.HelperText
import io.codemyriad.cassini.ui.components.SectionHeader
import io.codemyriad.cassini.ui.theme.CassiniTheme

/** Entire-row switch semantics, one node per choice; failed persistence never optimistically changes it. */
@Composable
internal fun BackupSection(state: BackupSettingsUi, onEvent: (SettingsEvent) -> Unit) {
    SectionHeader(stringResource(R.string.ui_settings_transfer_heading))
    GroupCard(Modifier.padding(horizontal = 16.dp)) {
        BackupSwitch(
            stringResource(R.string.ui_settings_transfer_title),
            stringResource(
                when {
                    !state.transferAvailable -> R.string.ui_settings_transfer_unavailable
                    state.transfer -> R.string.ui_settings_transfer_body
                    else -> R.string.ui_settings_transfer_body_off
                },
            ),
            state.transferAvailable && state.transfer,
            !state.saving && state.transferAvailable,
        ) { onEvent(SettingsEvent.Transfer(it)) }
    }
    HelperText(stringResource(R.string.ui_settings_transfer_export_hint))
    if (state.quotaExceeded) HelperText(stringResource(R.string.ui_settings_transfer_too_large))
    if (state.error) HelperText(stringResource(R.string.ui_settings_transfer_choice_failed))
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
