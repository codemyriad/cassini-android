// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 6.
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.cassini.ui.theme.CassiniTheme
import org.cassini.ui.theme.Tone
import org.cassini.ui.theme.colors

/**
 * The Library row's status chip (`chip` style, radius 10, padding 8/2): the tone's fill and text; [Tone.Outline] is
 * a 1 dp line2 border with ink text. The CSS border sits outside the padding, so an outlined chip is 2 dp larger,
 * as on the board. [dot] adds a 6 dp dot in the tone's dot colour ("Recording"). Not interactive.
 *
 * The top padding is 0.5 dp less than the board's: Android never sets one line of 13 sp below the font's own 32 px
 * at 2x, where the browser's line is 31 px, so the chip keeps the board's 19.5 dp (21.5 dp outlined).
 */
@Composable
fun StatusChip(text: String, tone: Tone, modifier: Modifier = Modifier, dot: Boolean = false) {
    val colors = tone.colors
    val shape = CassiniTheme.shapes.chip
    val border = colors.border
    val outlined = tone == Tone.Outline
    Row(
        modifier = modifier
            .clip(shape)
            .background(colors.bg)
            .then(if (outlined && border != null) Modifier.border(1.dp, border, shape) else Modifier)
            .padding(
                start = if (outlined) 9.dp else 8.dp,
                end = if (outlined) 9.dp else 8.dp,
                top = if (outlined) 2.5.dp else 1.5.dp,
                bottom = if (outlined) 3.dp else 2.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot && colors.dot.isSpecified) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(colors.dot),
            )
        }
        Text(text = text, style = CassiniTheme.type.chip, color = colors.fg, maxLines = 1)
    }
}

@Preview
@Composable
internal fun StatusChipPreview() {
    CassiniTheme {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip("Transcribing", Tone.Info)
            StatusChip("Queued", Tone.Outline)
            StatusChip("Recording", Tone.Error, dot = true)
        }
    }
}
