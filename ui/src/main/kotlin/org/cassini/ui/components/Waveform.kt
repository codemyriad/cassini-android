// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 6.
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import org.cassini.ui.R
import org.cassini.ui.theme.CassiniTheme
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** The tallest bar is this many dp at level 1. */
private const val MAX_BAR_DP = 92

/**
 * The Recording waveform (board 01): a 96 dp full-width canvas of [levels] (0–1, oldest first; 56 bars for the last
 * 7 s), 3 dp apart and equally wide. Each bar is max(4, round(level x 92)) dp tall, centred, with 2 dp corners; the
 * newest [freshCount] bars are rec.barFresh, the others rec.barIdle. One image node, "Microphone level, last 7
 * seconds".
 */
@Composable
fun Waveform(levels: ImmutableList<Float>, modifier: Modifier = Modifier, freshCount: Int = 7) {
    val rec = CassiniTheme.recording
    val description = stringResource(R.string.ui_recording_waveform)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(96.dp)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Image
            },
    ) {
        val count = levels.size
        if (count == 0) return@Canvas
        val gap = 3.dp.toPx()
        val width = (size.width - (count - 1) * gap) / count
        val radius = CornerRadius(2.dp.toPx())
        levels.forEachIndexed { index, level ->
            val height = maxOf(4, (level.coerceIn(0f, 1f) * MAX_BAR_DP).roundToInt()).dp.toPx()
            drawRoundRect(
                color = if (index >= count - freshCount) rec.barFresh else rec.barIdle,
                topLeft = Offset(index * (width + gap), (size.height - height) / 2f),
                size = Size(width, height),
                cornerRadius = radius,
            )
        }
    }
}

/** The board's 56 fixture levels: 0.18 + 0.82 x |sin(0.61 i) x cos(0.23 i + 1.1)|. */
internal fun boardWaveformLevels(count: Int = 56): ImmutableList<Float> =
    List(count) { i -> (0.18 + 0.82 * abs(sin(i * 0.61) * cos(i * 0.23 + 1.1))).toFloat() }.toImmutableList()

@Preview
@Composable
internal fun WaveformPreview() {
    CassiniTheme {
        Box(
            Modifier
                .width(390.dp)
                .background(CassiniTheme.recording.bg)
                .padding(16.dp),
        ) {
            Waveform(boardWaveformLevels())
        }
    }
}
