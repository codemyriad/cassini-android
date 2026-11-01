// Composables are nouns in PascalCase; the numbers are the values of docs/design/ui.md section 8 (no board).
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.dp
import org.cassini.ui.R
import org.cassini.ui.components.CassiniSheet
import org.cassini.ui.components.Radio
import org.cassini.ui.theme.CassiniTheme

/**
 * The App language sheet (no board; features.md section 11; coordination R3, D53) in its [CassiniSheet] window. A row
 * sends [onPick] with its choice; the screen closes the sheet first, because the new language creates the activity
 * again.
 */
@Composable
fun LanguageSheet(current: LanguageChoice, onPick: (LanguageChoice) -> Unit, onDismiss: () -> Unit) {
    CassiniSheet(onDismissRequest = onDismiss) { LanguageSheetContent(current, onPick) }
}

/**
 * The sheet's content, also drawn inline in screenshots: the title "App language" (`sheetTitle`, padding 20), then a
 * radio row per [LanguageChoice.offered] (at least 56 dp, padding 12/16 inside 4 dp: the radio and the name in
 * `rowTitle`). Phone is in the UI language; each language is named in its own language and spoken in it.
 */
@Composable
fun LanguageSheetContent(current: LanguageChoice, onPick: (LanguageChoice) -> Unit, modifier: Modifier = Modifier) {
    val c = CassiniTheme.colors
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(top = 16.dp, bottom = 24.dp),
    ) {
        Text(
            text = stringResource(R.string.ui_settings_language),
            modifier = Modifier
                .padding(start = 20.dp, end = 20.dp, bottom = 8.dp)
                .semantics { heading() },
            style = CassiniTheme.type.sheetTitle,
            color = c.ink,
        )
        Column(
            Modifier
                .padding(horizontal = 4.dp)
                .selectableGroup(),
        ) {
            LanguageChoice.offered.forEach { choice ->
                LanguageRow(choice, selected = choice == current, onPick = onPick)
            }
        }
    }
}

@Composable
private fun LanguageRow(choice: LanguageChoice, selected: Boolean, onPick: (LanguageChoice) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(CassiniTheme.shapes.row)
            .selectable(selected = selected, role = Role.RadioButton, onClick = { onPick(choice) })
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Radio(selected)
        Text(
            text = languageName(choice),
            modifier = Modifier.weight(1f),
            style = CassiniTheme.type.rowTitle.merge(spokenIn(choice)),
            color = CassiniTheme.colors.ink,
        )
    }
}

/**
 * The name of [choice]: "Phone" in the UI language, a language by its own name ("Italiano", "Português (Brasil)"),
 * never translated. [LanguageChoice.OTHER] is the current app locale by its own name ("Deutsch").
 */
@Composable
fun languageName(choice: LanguageChoice): String = when (choice) {
    LanguageChoice.PHONE -> stringResource(R.string.ui_settings_language_phone)
    LanguageChoice.ENGLISH -> stringResource(R.string.ui_language_english)
    LanguageChoice.ITALIAN -> stringResource(R.string.ui_language_italian)
    LanguageChoice.FRENCH -> stringResource(R.string.ui_language_french)
    LanguageChoice.SPANISH -> stringResource(R.string.ui_language_spanish)
    LanguageChoice.PORTUGUESE_BRAZIL -> stringResource(R.string.ui_language_portuguese_brazil)
    LanguageChoice.PORTUGUESE_PORTUGAL -> stringResource(R.string.ui_language_portuguese_portugal)
    LanguageChoice.OTHER -> {
        val locale = LocalConfiguration.current.locales[0]
        locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
    }
}

/** The locale that a screen reader speaks [choice]'s name in: the language itself; the UI's for Phone and Other. */
private fun spokenIn(choice: LanguageChoice): TextStyle =
    choice.tag?.let { TextStyle(localeList = LocaleList(it)) } ?: TextStyle()
