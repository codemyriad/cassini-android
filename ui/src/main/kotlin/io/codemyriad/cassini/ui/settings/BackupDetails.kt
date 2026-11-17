@file:Suppress("FunctionNaming")

package io.codemyriad.cassini.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.components.HelperText
import io.codemyriad.cassini.ui.components.SectionHeader
import io.codemyriad.cassini.ui.components.SettingsRow
import io.codemyriad.cassini.ui.components.SettingsTrailing
import io.codemyriad.cassini.ui.theme.Tone

/** Inclusion policy, not a claim that Android has completed a backup. Settings and model files never move. */
@Composable
internal fun BackupDetails(state: BackupSettingsUi) {
    val moves = state.cloudOn || state.transferOn
    SectionHeader(stringResource(R.string.ui_backup_moves), first = true)
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
    SectionHeader(stringResource(R.string.ui_backup_who))
    SettingsGroup {
        if (state.transferOn) {
            SettingsRow(
                stringResource(R.string.ui_backup_new_phone),
                subtitle = stringResource(R.string.ui_backup_new_phone_detail),
            )
        }
        if (state.cloudOn) {
            SettingsRow(
                stringResource(R.string.ui_backup_info_android_heading),
                subtitle = stringResource(R.string.ui_backup_info_android_body),
            )
        }
        SettingsRow(stringResource(R.string.ui_backup_zip), subtitle = stringResource(R.string.ui_backup_zip_detail))
    }
    HelperText(stringResource(R.string.ui_backup_never))
    BackupWarnings(state)
}

@Composable
private fun CopyRow(title: Int, detail: Int?, copied: Boolean) {
    SettingsRow(
        stringResource(title),
        subtitle = detail?.let { stringResource(it) },
        trailing = SettingsTrailing.Status(
            stringResource(if (copied) R.string.ui_backup_copied else R.string.ui_backup_not_copied),
            if (copied) Tone.Ok else Tone.Plain,
        ),
    )
}
