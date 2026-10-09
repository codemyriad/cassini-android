// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 6.
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.cassini.ui.theme.CassiniTheme

/** "Likely": `tag12` (600) in accentInk on accentTint, radius 8, padding 2/8. */
@Composable
fun LikelyTag(text: String, modifier: Modifier = Modifier) {
    val c = CassiniTheme.colors
    Text(
        text = text,
        modifier = modifier
            .clip(CassiniTheme.shapes.tag)
            .background(c.accentTint)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        style = CassiniTheme.type.tag12,
        color = c.accentInk,
        maxLines = 1,
    )
}

/**
 * "Words may still change": `tag12` (400) in text2 inside a 1 dp dashed line3 border (dashes 3 dp, gaps 2 dp),
 * radius 8. The border sits outside the 2/8 padding, as the CSS border.
 */
@Composable
fun DashedTag(text: String, modifier: Modifier = Modifier) {
    val c = CassiniTheme.colors
    Text(
        text = text,
        modifier = modifier
            .drawBehind {
                val width = 1.dp.toPx()
                val radius = 8.dp.toPx()
                drawRoundRect(
                    color = c.line3,
                    topLeft = Offset(width / 2, width / 2),
                    size = Size(size.width - width, size.height - width),
                    cornerRadius = CornerRadius(radius - width / 2),
                    style = Stroke(
                        width = width,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())),
                    ),
                )
            }
            .padding(horizontal = 9.dp, vertical = 3.dp),
        style = CassiniTheme.type.tag12Plain,
        color = c.text2,
        maxLines = 1,
    )
}

/** The "auto" badge after an automatically named speaker: `badge` in text2, 1 dp line2 border, radius 6, padding 0/5. */
@Composable
fun AutoBadge(text: String, modifier: Modifier = Modifier) {
    val c = CassiniTheme.colors
    Text(
        text = text,
        modifier = modifier
            .border(1.dp, c.line2, CassiniTheme.shapes.badge)
            .padding(horizontal = 6.dp, vertical = 1.dp),
        style = CassiniTheme.type.badge,
        color = c.text2,
        maxLines = 1,
    )
}

/** The Recording header's format badge ("Opus 48 kHz"): `monoSmall` in rec.text2, 1 dp rec.line border, radius 8, padding 4/8. */
@Composable
fun FormatBadge(text: String, modifier: Modifier = Modifier) {
    val rec = CassiniTheme.recording
    Text(
        text = text,
        modifier = modifier
            .border(1.dp, rec.line, CassiniTheme.shapes.tag)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        style = CassiniTheme.type.monoSmall,
        color = rec.text2,
        maxLines = 1,
    )
}

@Preview
@Composable
internal fun TagsPreview() {
    CassiniTheme {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            LikelyTag("Likely")
            DashedTag("Words may still change")
            AutoBadge("auto")
        }
    }
}
