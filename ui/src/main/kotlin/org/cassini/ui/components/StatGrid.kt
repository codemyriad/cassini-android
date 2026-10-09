// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 6. The file
// is named after its composable, not after the StatItem class it also holds.
@file:Suppress("FunctionNaming", "MagicNumber", "MatchingDeclarationName")

package org.cassini.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import org.cassini.ui.theme.CassiniTheme

/**
 * One cell of a [StatGrid]: [label] over [value]. [mono] sets the value in `monoValue` ("Processed"); [pending]
 * sets it in 15 sp 400 text2 ("Calculating…", "Measuring…").
 */
@Immutable
data class StatItem(val label: String, val value: String, val mono: Boolean = false, val pending: Boolean = false)

/**
 * The stats of the Transcribing card (board 02): two equal columns, 10 dp between rows and 12 dp between columns;
 * the label in `caption` text2, 2 dp, the value in `button` ink. Each cell is read as one node ("Time left, 6–9 min").
 */
@Composable
fun StatGrid(items: ImmutableList<StatItem>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { StatCell(it, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatCell(item: StatItem, modifier: Modifier) {
    val c = CassiniTheme.colors
    val t = CassiniTheme.type
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(text = item.label, style = t.caption, color = c.text2)
        Spacer(Modifier.height(2.dp))
        Text(
            text = item.value,
            style = when {
                item.pending -> t.buttonRegular
                item.mono -> t.monoValue
                else -> t.button
            },
            color = if (item.pending) c.text2 else c.ink,
        )
    }
}

@Preview
@Composable
internal fun StatGridPreview() {
    CassiniTheme {
        StatGrid(
            persistentListOf(
                StatItem("Time left", "6–9 min"),
                StatItem("Processed", "29:52 / 48:13", mono = true),
                StatItem("Battery at finish", "Measuring…", pending = true),
            ),
            Modifier
                .width(356.dp)
                .background(CassiniTheme.colors.surface)
                .padding(8.dp),
        )
    }
}
