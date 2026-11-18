@file:Suppress("FunctionNaming", "MagicNumber")

package io.codemyriad.cassini.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.components.HelperText
import io.codemyriad.cassini.ui.components.SectionHeader
import io.codemyriad.cassini.ui.components.SettingsRow
import io.codemyriad.cassini.ui.components.SettingsTrailing
import io.codemyriad.cassini.ui.theme.CassiniTheme
import io.codemyriad.cassini.ui.theme.Tone

/** Inclusion policy, not a claim that Android has completed a backup. Settings and model files never move. */
@Composable
internal fun BackupDetails(state: BackupSettingsUi) {
    val moves = state.cloudOn || state.transferOn
    SectionHeader(stringResource(R.string.ui_backup_included_heading), first = true)
    SettingsGroup {
        CopyRow(R.string.ui_backup_meetings, R.string.ui_backup_meetings_detail, moves)
        CopyRow(
            R.string.ui_settings_voices,
            if (moves && state.voices) R.string.ui_backup_voices_with_meetings else null,
            moves && state.voices,
        )
        CopyRow(R.string.ui_backup_settings, R.string.ui_backup_settings_detail, false)
        CopyRow(R.string.ui_backup_models, R.string.ui_backup_models_detail, false)
    }
    SectionHeader(stringResource(R.string.ui_backup_who), topPadding = 32.dp)
    Column(
        Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.transferOn) {
            WhoCanReadItem(R.string.ui_backup_new_phone, R.string.ui_backup_new_phone_detail)
        }
        if (state.cloudOn) {
            WhoCanReadItem(R.string.ui_backup_info_android_heading, R.string.ui_backup_info_android_body)
        }
        WhoCanReadItem(R.string.ui_backup_zip, R.string.ui_backup_zip_detail)
    }
    HelperText(stringResource(R.string.ui_backup_never))
    BackupWarnings(state)
}

@Composable
private fun WhoCanReadItem(title: Int, detail: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(stringResource(title), style = CassiniTheme.type.button, color = CassiniTheme.colors.ink)
        Text(stringResource(detail), style = CassiniTheme.type.body14, color = CassiniTheme.colors.text2)
    }
}

@Composable
private fun CopyRow(title: Int, detail: Int?, copied: Boolean) {
    SettingsRow(
        stringResource(title),
        subtitle = detail?.let { stringResource(it) },
        trailing = SettingsTrailing.Status(
            stringResource(if (copied) R.string.ui_backup_included else R.string.ui_backup_not_included),
            if (copied) Tone.Ok else Tone.Plain,
        ),
    )
}
