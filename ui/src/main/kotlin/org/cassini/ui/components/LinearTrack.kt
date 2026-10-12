// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 6. The file
// is named after its composable, not after the TrackHeight enum it also holds.
@file:Suppress("FunctionNaming", "MagicNumber", "MatchingDeclarationName")

package org.cassini.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.cassini.ui.theme.CassiniTheme

/**
 * The two progress tracks: [Thin] 4 dp (radius 2; accentTrack under accent: the Library banner) and [Thick] 6 dp
 * (radius 3; line under accent, the fill rounded too: the progress card).
 */
enum class TrackHeight(val height: Dp) { Thin(4.dp), Thick(6.dp) }

/**
 * A determinate track filled to [progress] (0–1, clamped). Decorative: the card around it says the percentage, so
 * the track adds no semantics of its own.
 */
@Composable
fun LinearTrack(
    progress: Float,
    modifier: Modifier = Modifier,
    height: TrackHeight = TrackHeight.Thin,
    trackColor: Color = Color.Unspecified,
    fillColor: Color = Color.Unspecified,
) {
    val c = CassiniTheme.colors
    val thin = height == TrackHeight.Thin
    val shape = if (thin) CassiniTheme.shapes.track4 else CassiniTheme.shapes.track6
    val track = if (trackColor != Color.Unspecified) trackColor else if (thin) c.accentTrack else c.line
    val fill = if (fillColor != Color.Unspecified) fillColor else c.accent
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height.height)
            .clip(shape)
            .background(track),
    ) {
        val fraction = progress.coerceIn(0f, 1f)
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .then(if (thin) Modifier else Modifier.clip(shape))
                    .background(fill),
            )
        }
    }
}

@Preview
@Composable
internal fun LinearTrackPreview() {
    CassiniTheme {
        Column(Modifier.width(300.dp).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LinearTrack(0.62f)
            LinearTrack(0.62f, height = TrackHeight.Thick)
        }
    }
}
