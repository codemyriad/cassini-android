// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 6.
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import org.cassini.ui.theme.CassiniTheme

/**
 * The App language control (board 08): surface, radius 16, 52 dp. Equal segments with full-height click areas; the
 * visual of each is a 44 dp rect (radius 12) inset 4 dp from the card and 4 dp from its neighbours (2 dp each side).
 * Selected: inkFill with `label14` in onInkFill; others `body14Plain` in ink. A selectable group of radio buttons.
 */
@Composable
fun SegmentedControl(
    options: ImmutableList<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = CassiniTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(CassiniTheme.shapes.card)
            .background(c.surface)
            .padding(horizontal = 2.dp)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val interaction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp)
                    .selectable(
                        selected = selected,
                        interactionSource = interaction,
                        indication = null,
                        role = Role.RadioButton,
                        onClick = { onSelect(index) },
                    )
                    .padding(horizontal = 2.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clip(CassiniTheme.shapes.segment)
                        .background(if (selected) c.inkFill else Color.Transparent)
                        .indication(interaction, ripple())
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = if (selected) CassiniTheme.type.label14 else CassiniTheme.type.body14Plain,
                        color = if (selected) c.onInkFill else c.ink,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Preview
@Composable
internal fun SegmentedControlPreview() {
    CassiniTheme {
        Box(
            Modifier
                .background(CassiniTheme.colors.bg)
                .padding(16.dp),
        ) {
            SegmentedControl(persistentListOf("Phone", "English", "Italiano"), selectedIndex = 0, onSelect = {})
        }
    }
}
