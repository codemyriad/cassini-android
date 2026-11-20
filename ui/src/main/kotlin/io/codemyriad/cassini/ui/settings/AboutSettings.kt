@file:Suppress("FunctionNaming", "MagicNumber")

package io.codemyriad.cassini.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.components.HelperText
import io.codemyriad.cassini.ui.licenses.SOURCE_CODE_URL
import io.codemyriad.cassini.ui.theme.CassiniTheme
import io.codemyriad.cassini.ui.theme.Glyph

@Composable
internal fun AboutSettings(state: SettingsUiState, onEvent: (SettingsEvent) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(R.drawable.ui_saturn_mid), null, Modifier.size(80.dp, 56.dp))
        Text(stringResource(R.string.ui_brand), Modifier.padding(top = 16.dp), style = CassiniTheme.type.sheetTitle)
        Text(
            stringResource(R.string.ui_about_version, state.version),
            style = CassiniTheme.type.body14,
            color = CassiniTheme.colors.text2,
        )
        Text(
            stringResource(R.string.ui_about_tagline),
            Modifier.padding(top = 12.dp),
            style = CassiniTheme.type.body16,
            textAlign = TextAlign.Center,
            color = CassiniTheme.colors.text2,
        )
    }
    SettingsGroup {
        AboutLink(R.string.ui_about_help, stringResource(R.string.ui_about_help_subtitle)) {
            onEvent(SettingsEvent.OpenHelp)
        }
        AboutLink(R.string.ui_about_report_problem, stringResource(R.string.ui_about_report_problem_subtitle)) {
            onEvent(SettingsEvent.OpenReportProblem)
        }
        AboutLink(R.string.ui_about_privacy, stringResource(R.string.ui_about_privacy_subtitle)) {
            onEvent(SettingsEvent.OpenPrivacy)
        }
        AboutLink(R.string.ui_about_licences, stringResource(R.string.ui_about_licences_subtitle)) {
            onEvent(SettingsEvent.OpenLicenses)
        }
        AboutLink(R.string.ui_about_source, SOURCE_CODE_URL.removePrefix("https://")) {
            onEvent(SettingsEvent.OpenSourceCode)
        }
    }
    HelperText(stringResource(R.string.ui_about_licence_line, state.copyrightYear))
}

@Composable
private fun AboutLink(title: Int, subtitle: String, onClick: () -> Unit) {
    val glyph = when (title) {
        R.string.ui_about_privacy -> Glyph.Shield
        R.string.ui_about_licences -> Glyph.TextFile
        R.string.ui_about_source -> Glyph.Share
        else -> Glyph.Info
    }
    MapRow(stringResource(title), "", glyph, subtitle, onClick)
}
