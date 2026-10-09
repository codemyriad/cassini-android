// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 6.
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.cassini.ui.theme.CassiniTheme
import org.cassini.ui.theme.SpeakerTone
import org.cassini.ui.theme.colors

/**
 * A round avatar of [size] dp (boards 04 and 07): the speaker tone's tint with [text] (an initial or a number) in
 * the tone's ink colour, `avatar` style (16 sp, 700). Decorative: the row or header names the person.
 */
@Composable
fun Avatar(text: String, tone: SpeakerTone, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val colors = tone.colors
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(colors.tint),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = CassiniTheme.type.avatar, color = colors.ink, maxLines = 1)
    }
}

@Preview
@Composable
internal fun AvatarPreview() {
    CassiniTheme {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Avatar("G", SpeakerTone.Blue)
            Avatar("P", SpeakerTone.Teal)
            Avatar("A", SpeakerTone.Amber)
            Avatar("L", SpeakerTone.Violet)
        }
    }
}
