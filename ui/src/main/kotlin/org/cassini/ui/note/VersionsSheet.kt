// Composables are nouns in PascalCase; the numbers are the values of docs/design/ui.md section 8 (no board).
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.note

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.cassini.ui.R
import org.cassini.ui.components.CassiniSheet
import org.cassini.ui.components.DISABLED_ALPHA
import org.cassini.ui.format.Counts
import org.cassini.ui.format.Dates
import org.cassini.ui.format.Languages
import org.cassini.ui.theme.CassiniTheme

/**
 * The Versions sheet (no board; features.md section 3.4; ui.md section 8) in its [CassiniSheet] window. A row sends
 * [onPick] with the variant index; the ViewModel selects it and closes the sheet.
 */
@Composable
fun VersionsSheet(ui: VersionsUi, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    CassiniSheet(onDismissRequest = onDismiss) { VersionsSheetContent(ui, onPick) }
}

/**
 * The sheet's content, also drawn inline in screenshots: the title "Versions" (`sheetTitle`, padding 20), then one
 * radio row per variant in manifest order (at least 64 dp, padding 12/16 inside 4 dp: the radio, "Version N" in
 * `rowTitle` and the detail in `caption` text2). An unreadable variant is disabled at 38 % with "Can’t be read".
 */
@Composable
fun VersionsSheetContent(ui: VersionsUi, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = CassiniTheme.colors
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(top = 16.dp, bottom = 24.dp),
    ) {
        Text(
            text = stringResource(R.string.ui_versions_title),
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
            ui.rows.forEach { row -> VersionRow(row, selected = row.index == ui.selected, onPick = onPick) }
        }
    }
}

@Composable
private fun VersionRow(row: VersionRowUi, selected: Boolean, onPick: (Int) -> Unit) {
    val c = CassiniTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(CassiniTheme.shapes.row)
            .selectable(
                selected = selected,
                enabled = row.readable,
                role = Role.RadioButton,
                onClick = { onPick(row.index) },
            )
            .alpha(if (row.readable) 1f else DISABLED_ALPHA)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Radio(selected)
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.ui_versions_row, row.number),
                style = CassiniTheme.type.rowTitle,
                color = c.ink,
            )
            Text(text = versionDetail(row), style = CassiniTheme.type.caption, color = c.text2)
        }
    }
}

/** A 22 dp radio: a 2 dp accent ring with a 12 dp accent dot when [selected], else a 2 dp text2 ring. */
@Composable
private fun Radio(selected: Boolean) {
    val c = CassiniTheme.colors
    Box(
        Modifier
            .size(22.dp)
            .border(2.dp, if (selected) c.accent else c.text2, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                Modifier
                    .size(12.dp)
                    .background(c.accent, CircleShape),
            )
        }
    }
}

/**
 * "Transcribed 8 Oct, 15:40 · 4,812 words" or "Speakers merged · from version 1 · 8 Oct, 16:02", then " · Italian"
 * when the language is known; "Can’t be read" for an unreadable variant. The year is left out: the row has no
 * "today" to compare with (ui.md section 2), and variants of one note are close in time.
 */
@Composable
private fun versionDetail(row: VersionRowUi): String {
    val at = Dates.shortDateTime(row.date, row.time, withYear = false)
    val from = row.fromVersion
    val detail = when {
        !row.readable -> stringResource(R.string.ui_versions_unreadable)
        row.kind == VersionKindUi.MERGED && from != null -> stringResource(R.string.ui_versions_merged, from, at)
        else -> stringResource(
            R.string.ui_versions_transcribed,
            at,
            Counts.plural(R.plurals.ui_count_words, row.words),
        )
    }
    val language = row.language
    return if (language == null || !row.readable) {
        detail
    } else {
        stringResource(R.string.ui_join_dot, detail, Languages.name(language))
    }
}
