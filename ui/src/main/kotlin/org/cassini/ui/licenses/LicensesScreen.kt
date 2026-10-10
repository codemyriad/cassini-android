// Composables are nouns in PascalCase; the numbers are the values of docs/design/ui.md section 8.
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.licenses

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.cassini.ui.R
import org.cassini.ui.components.CassiniTopBar
import org.cassini.ui.components.GroupCard
import org.cassini.ui.components.SettingsRow
import org.cassini.ui.components.SettingsTrailing
import org.cassini.ui.theme.CassiniTheme

/** The repository the "Source code on GitHub" row opens (features.md section 11). */
const val SOURCE_CODE_URL = "https://github.com/codemyriad/cassini-android"

/**
 * Licenses, "Source code and notices" (no board; features.md section 11, ui.md section 8). The list is a
 * [GroupCard] of [SettingsRow]s (title, the licence or a short description, ChevronRight) under the top bar. An
 * opened row ([LicensesUiState.open]) shows its text in a full-screen pane with its own top bar: licence texts in
 * `monoSmall` text3, notices and the privacy text in `body14` as plain paragraphs; both selectable, padding 20.
 * System back in the pane closes it.
 */
@Composable
fun LicensesScreen(
    state: LicensesUiState,
    onEvent: (LicensesEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val open = state.open
    BackHandler(enabled = open != null) { onEvent(LicensesEvent.CloseText) }
    val base = modifier
        .fillMaxSize()
        .background(CassiniTheme.colors.bg)
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
    if (open != null) {
        LicenseTextPane(open, onEvent, base)
        return
    }
    Column(
        base
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(bottom = 24.dp),
    ) {
        CassiniTopBar(
            title = stringResource(R.string.ui_licenses_title),
            onBack = { onEvent(LicensesEvent.Back) },
            backLabel = stringResource(R.string.ui_back_to_settings),
        )
        GroupCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
            state.entries.forEach { entry ->
                SettingsRow(
                    title = entry.title(),
                    subtitle = entry.subtitle(),
                    trailing = SettingsTrailing.Chevron,
                    onClick = { onEvent(LicensesEvent.Open(entry)) },
                )
            }
        }
    }
}

/** The text of an opened row, under a top bar with the row's title; its Back closes the pane. */
@Composable
private fun LicenseTextPane(open: LicenseText, onEvent: (LicensesEvent) -> Unit, modifier: Modifier) {
    val licence = open.entry.isLicenceText()
    val type = CassiniTheme.type
    val paragraphs = remember(open.text) { paragraphs(open.text) }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(modifier) {
        CassiniTopBar(
            title = open.entry.title(),
            onBack = { onEvent(LicensesEvent.CloseText) },
            backLabel = stringResource(R.string.ui_back),
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = bottom + 20.dp),
            verticalArrangement = Arrangement.spacedBy(if (licence) 12.dp else 10.dp),
        ) {
            items(paragraphs) { paragraph ->
                SelectionContainer {
                    Text(
                        text = paragraph,
                        style = if (licence) type.monoSmall else type.body14,
                        color = if (licence) CassiniTheme.colors.text3 else CassiniTheme.colors.ink,
                    )
                }
            }
        }
    }
}

/** The text split at blank lines, each paragraph trimmed. */
private fun paragraphs(text: String): List<String> =
    text.split(Regex("\n\\s*\n")).map(String::trim).filter(String::isNotEmpty)

/** The licence texts (GPLv3, the font licences) are set in mono; the notices and the privacy text in body type. */
private fun LicenseEntry.isLicenceText(): Boolean =
    this == LicenseEntry.CASSINI || this == LicenseEntry.INSTRUMENT_SANS || this == LicenseEntry.PLEX_MONO

@Composable
private fun LicenseEntry.title(): String = when (this) {
    LicenseEntry.CASSINI -> stringResource(R.string.ui_brand)
    LicenseEntry.THIRD_PARTY -> stringResource(R.string.ui_licenses_third_party)
    LicenseEntry.NATIVE -> stringResource(R.string.ui_licenses_native)
    LicenseEntry.INSTRUMENT_SANS -> stringResource(R.string.ui_font_instrument_sans)
    LicenseEntry.PLEX_MONO -> stringResource(R.string.ui_font_plex_mono)
    LicenseEntry.MODELS -> stringResource(R.string.ui_licenses_models)
    LicenseEntry.MODEL_SOURCES -> stringResource(R.string.ui_licenses_sources)
    LicenseEntry.PRIVACY -> stringResource(R.string.ui_licenses_privacy)
    LicenseEntry.SOURCE_CODE -> stringResource(R.string.ui_licenses_source_code)
}

@Composable
private fun LicenseEntry.subtitle(): String = when (this) {
    LicenseEntry.CASSINI -> stringResource(R.string.ui_licence_gpl)
    LicenseEntry.THIRD_PARTY -> stringResource(R.string.ui_licenses_third_party_body)
    LicenseEntry.NATIVE -> stringResource(R.string.ui_licenses_native_body)
    LicenseEntry.INSTRUMENT_SANS, LicenseEntry.PLEX_MONO -> stringResource(R.string.ui_licence_ofl)
    LicenseEntry.MODELS -> stringResource(R.string.ui_licenses_models_body)
    LicenseEntry.MODEL_SOURCES -> stringResource(R.string.ui_licenses_sources_body)
    LicenseEntry.PRIVACY -> stringResource(R.string.ui_licenses_privacy_body)
    LicenseEntry.SOURCE_CODE -> SOURCE_CODE_URL.removePrefix("https://")
}
