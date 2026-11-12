@file:Suppress("FunctionNaming", "MagicNumber") // Canvas Badge-Android metrics (L-26).

package io.codemyriad.cassini.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.codemyriad.cassini.ui.theme.CassiniTheme

/** One noninteractive pill. Text and its decorative glyph share the same foreground colour. */
@Composable
fun StatusChip(text: String, kind: StatusBadgeKind, modifier: Modifier = Modifier) {
    val c = CassiniTheme.colors
    val received = kind.tone == StatusBadgeTone.Outline
    val background = when (kind.tone) {
        StatusBadgeTone.Red -> c.recordTint
        StatusBadgeTone.Amber -> c.amberTint
        StatusBadgeTone.Outline -> Color.Transparent
        StatusBadgeTone.Neutral -> c.neutralTint
    }
    val foreground = when (kind.tone) {
        StatusBadgeTone.Red -> c.recordInk
        StatusBadgeTone.Amber -> c.amberInk
        StatusBadgeTone.Neutral, StatusBadgeTone.Outline -> c.text2
    }
    Row(
        modifier = modifier
            .heightIn(min = BadgeHeight)
            .background(background, CircleShape)
            .then(if (received) Modifier.border(1.dp, c.line, CircleShape) else Modifier)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BadgeGlyph(kind, foreground)
        Text(text, style = CassiniTheme.type.chip, color = foreground, maxLines = 1)
    }
}

/** Static spinner arc as drawn by the canvas, so screenshots never depend on an animation frame. */
@Composable
private fun BadgeGlyph(kind: StatusBadgeKind, color: Color) {
    val size = when (kind.glyph) {
        StatusBadgeGlyph.Dot -> 6.dp
        StatusBadgeGlyph.Spinner -> 10.dp
        else -> 11.dp
    }
    Canvas(Modifier.size(size).testTag("badge-glyph-$kind")) {
        val unit = this.size.width / if (kind.glyph == StatusBadgeGlyph.Incoming) 24f else 16f
        when (kind.glyph) {
            StatusBadgeGlyph.Dot -> drawCircle(color)
            StatusBadgeGlyph.Spinner -> {
                val stroke = Stroke(2.4f * unit, cap = StrokeCap.Round)
                drawCircle(color.copy(alpha = 0.3f), 6f * unit, Offset(8f * unit, 8f * unit), style = stroke)
                drawArc(
                    color,
                    -90f,
                    90f,
                    false,
                    Offset(2f * unit, 2f * unit),
                    Size(12f * unit, 12f * unit),
                    style = stroke,
                )
            }
            StatusBadgeGlyph.Alert -> {
                drawCircle(color, 6.5f * unit, Offset(8f * unit, 8f * unit), style = Stroke(1.8f * unit))
                drawLine(
                    color,
                    Offset(8f * unit, 4.8f * unit),
                    Offset(8f * unit, 8.6f * unit),
                    1.9f * unit,
                    StrokeCap.Round,
                )
                drawCircle(color, 0.95f * unit, Offset(8f * unit, 11f * unit))
            }
            StatusBadgeGlyph.Incoming -> {
                val arrow = Path().apply {
                    moveTo(12f * unit, 4f * unit)
                    lineTo(12f * unit, 14f * unit)
                    moveTo(8f * unit, 10f * unit)
                    lineTo(12f * unit, 14f * unit)
                    lineTo(16f * unit, 10f * unit)
                    moveTo(4f * unit, 16f * unit)
                    lineTo(4f * unit, 19.5f * unit)
                    lineTo(20f * unit, 19.5f * unit)
                    lineTo(20f * unit, 16f * unit)
                }
                drawPath(arrow, color, style = Stroke(2.4f * unit, cap = StrokeCap.Round))
            }
        }
    }
}

private val BadgeHeight = 22.dp
