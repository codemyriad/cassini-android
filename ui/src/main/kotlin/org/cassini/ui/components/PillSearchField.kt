// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 6.
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.cassini.ui.R
import org.cassini.ui.theme.CassiniIcon
import org.cassini.ui.theme.CassiniTheme
import org.cassini.ui.theme.Glyph

/**
 * The pill search field: 54 dp, surface, 1 dp line, padding 16; Search 20/1.8 in text2, 10 dp, then a
 * [BasicTextField] (`body16` ink, 2 dp extra start inset, accent cursor, IME action Search) with [placeholder] in
 * text4. A 48 dp "Clear search" button ends the pill while [query] is not empty. [compact] is the in-note variant:
 * visual 44 / layout 48.
 *
 * ui.md section 6 gives 52 dp: that is the board's CSS content height, and its 1 dp border sits outside it
 * (content-box), so board 00 draws the pill 54 dp high.
 */
@Composable
@Suppress("LongParameterList", "LongMethod") // Query, its callbacks, placeholder and the compact variant.
fun PillSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSearch: () -> Unit = {},
    onClear: () -> Unit = { onQueryChange("") },
    compact: Boolean = false,
) {
    val c = CassiniTheme.colors
    val shape = CassiniTheme.shapes.pill
    // The pill is drawn behind the row, 2 dp inside the 48 dp layout when compact, so the 48 dp clear button can
    // overflow the 44 dp pill instead of making it taller.
    val inset = if (compact) 2.dp else 0.dp
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (compact) 48.dp else 54.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .matchParentSize()
                .padding(vertical = inset)
                .clip(shape)
                .background(c.surface)
                .border(1.dp, c.line, shape),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (compact) 44.dp else 54.dp)
                .padding(start = 16.dp, end = if (query.isEmpty()) 16.dp else 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CassiniIcon(Glyph.Search, 20.dp, 1.8f, c.text2, null)
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 2.dp),
                textStyle = CassiniTheme.type.body16.copy(color = c.ink),
                cursorBrush = SolidColor(c.accent),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(text = placeholder, style = CassiniTheme.type.body16, color = c.text4, maxLines = 1)
                        }
                        inner()
                    }
                },
            )
            if (query.isNotEmpty()) {
                IconButton44(
                    glyph = Glyph.Close,
                    contentDescription = stringResource(R.string.ui_search_clear),
                    onClick = onClear,
                    iconSize = 20.dp,
                    stroke = 1.8f,
                    tint = c.text2,
                )
            }
        }
    }
}

@Preview
@Composable
internal fun PillSearchFieldPreview() {
    CassiniTheme {
        Box(
            Modifier
                .background(CassiniTheme.colors.bg)
                .padding(16.dp),
        ) {
            PillSearchField(query = "", onQueryChange = {}, placeholder = "Search titles and what was said")
        }
    }
}
