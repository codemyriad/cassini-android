// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 6.
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import org.cassini.ui.theme.CassiniTheme

/** M3's menu geometry: 8 dp above and below the items, items 112–280 dp wide and at least 48 dp tall. */
private val MenuVerticalPadding = 8.dp
private val MenuItemMinWidth = 112.dp
private val MenuItemMaxWidth = 280.dp

/**
 * The app's popup menu (Saved voices "Options for <name>"): an M3 `DropdownMenu` on surface with radius 14, a 1 dp
 * line border and no elevation or shadow. Fill it with [CassiniMenuItem]s. Screenshot tests draw the same card
 * inline with [CassiniMenuContent] (`MenuBoardFrame` in test sources).
 */
@Composable
fun CassiniMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = CassiniTheme.colors
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = offset,
        shape = CassiniTheme.shapes.row,
        containerColor = c.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, c.line),
        content = content,
    )
}

/** The menu card as `DropdownMenu` draws it, for inline frames: the [CassiniMenu] colours, shape and padding. */
@Composable
fun CassiniMenuContent(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = CassiniTheme.colors
    val shape = CassiniTheme.shapes.row
    Column(
        modifier = modifier
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.line, shape)
            .padding(vertical = MenuVerticalPadding)
            .width(IntrinsicSize.Max),
        content = content,
    )
}

/** A menu item: min 48 dp tall, 112–280 dp wide, padding 16 horizontal, `body16` in ink ([destructive]: redInk). */
@Composable
fun CassiniMenuItem(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, destructive: Boolean = false) {
    val c = CassiniTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .sizeIn(minWidth = MenuItemMinWidth, maxWidth = MenuItemMaxWidth, minHeight = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = text,
            style = CassiniTheme.type.body16,
            color = if (destructive) c.redInk else c.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Preview
@Composable
internal fun CassiniMenuPreview() {
    CassiniTheme {
        Box(
            Modifier
                .background(CassiniTheme.colors.bg)
                .padding(16.dp),
        ) {
            CassiniMenuContent {
                CassiniMenuItem("Rename", onClick = {})
                CassiniMenuItem("Forget", onClick = {}, destructive = true)
            }
        }
    }
}
