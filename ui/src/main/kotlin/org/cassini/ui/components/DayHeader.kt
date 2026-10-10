// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 6.
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.cassini.ui.format.LocalFormat
import org.cassini.ui.theme.CassiniTheme

/**
 * A Library day group header: `overline` in text2, uppercase; padding 20 horizontal, bottom 6, top 14 ([first]) or
 * 16. The bottom padding is 5.5 dp: one 13 sp line is 0.5 dp taller on Android than in the browser (see StatusChip).
 */
@Composable
fun DayHeader(text: String, modifier: Modifier = Modifier, first: Boolean = false) {
    Text(
        text = text.uppercase(LocalFormat.current.locale),
        modifier = modifier
            .padding(start = 20.dp, end = 20.dp, top = if (first) 14.dp else 16.dp, bottom = 5.5.dp)
            .semantics { heading() },
        style = CassiniTheme.type.overline,
        color = CassiniTheme.colors.text2,
    )
}

@Preview
@Composable
internal fun DayHeaderPreview() {
    CassiniTheme {
        Column(Modifier.background(CassiniTheme.colors.bg)) {
            DayHeader("Today", first = true)
            DayHeader("Yesterday")
        }
    }
}
