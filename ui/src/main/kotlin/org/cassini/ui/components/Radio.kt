// Composables are nouns in PascalCase; the numbers are the values of docs/design/ui.md section 8.
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.cassini.ui.theme.CassiniTheme

/**
 * A 22 dp radio of a sheet row (Versions, App language): a 2 dp accent ring with a 12 dp accent dot when [selected],
 * else a 2 dp text2 ring. It draws only; the row is the `selectable` radio button.
 */
@Composable
internal fun Radio(selected: Boolean, modifier: Modifier = Modifier) {
    val c = CassiniTheme.colors
    Box(
        modifier
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
