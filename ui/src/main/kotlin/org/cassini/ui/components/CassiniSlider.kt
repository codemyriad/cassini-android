// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 6.
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.cassini.ui.R
import org.cassini.ui.format.Spoken
import org.cassini.ui.theme.CassiniTheme

/** The thumb's diameter; M3 insets the track by half of it on each side. */
private val ThumbSize = 16.dp
private val TrackThickness = 8.dp

/** Accessibility moves the position in steps of this many milliseconds. */
const val SLIDER_STEP_MS = 10_000L

/**
 * The player's position slider: an M3 `Slider` (48 dp layout) with a custom 8 dp track (radius 4; accent up to the
 * thumb, boardBg with a 1 dp line2 border after it) spanning the full width, and a 16 dp accent thumb. Dragging calls
 * [onSeek] with the new position in milliseconds; [onSeekFinished] when the drag ends.
 *
 * Accessibility: one node "Position" with the state "11:44 of 23:50"; TalkBack's adjust actions move by
 * [SLIDER_STEP_MS] (the reported steps), while touch stays continuous.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongParameterList") // The position, its callbacks and the enabled state.
fun CassiniSlider(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onSeekFinished: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val c = CassiniTheme.colors
    val max = durationMs.coerceAtLeast(1L).toFloat()
    val value = positionMs.coerceIn(0L, durationMs.coerceAtLeast(0L)).toFloat()
    val label = stringResource(R.string.ui_player_position)
    val state = Spoken.position(positionMs, durationMs)
    val steps = (durationMs / SLIDER_STEP_MS - 1).coerceAtLeast(0L).toInt()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = label
                stateDescription = state
                progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..max, steps)
                if (enabled) {
                    setProgress { target ->
                        onSeek(target.coerceIn(0f, max).toLong())
                        onSeekFinished?.invoke()
                        true
                    }
                }
            },
    ) {
        Slider(
            value = value,
            onValueChange = { onSeek(it.toLong()) },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            onValueChangeFinished = onSeekFinished,
            valueRange = 0f..max,
            thumb = {
                Box(
                    Modifier
                        .size(ThumbSize)
                        .clip(CircleShape)
                        .background(c.accent),
                )
            },
            track = { sliderState -> SliderTrack(sliderState) },
        )
    }
}

/**
 * The track. M3 lays it out between the thumb's centre positions, so it draws half a thumb further on each side to
 * span the slider's full width, as the board's range input does.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SliderTrack(state: SliderState) {
    val c = CassiniTheme.colors
    val range = state.valueRange
    val span = range.endInclusive - range.start
    val fraction = if (span > 0f) ((state.value - range.start) / span).coerceIn(0f, 1f) else 0f
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(TrackThickness),
    ) {
        val inset = ThumbSize.toPx() / 2f
        val radius = CornerRadius(size.height / 2f)
        val border = 1.dp.toPx()
        val full = Size(size.width + 2 * inset, size.height)
        drawRoundRect(c.boardBg, Offset(-inset, 0f), full, radius)
        drawRoundRect(
            color = c.line2,
            topLeft = Offset(-inset + border / 2f, border / 2f),
            size = Size(full.width - border, full.height - border),
            cornerRadius = CornerRadius(radius.x - border / 2f),
            style = Stroke(border),
        )
        val activeEnd = fraction * size.width
        drawRoundRect(c.accent, Offset(-inset, 0f), Size(activeEnd + inset, size.height), radius)
    }
}

@Preview
@Composable
internal fun CassiniSliderPreview() {
    CassiniTheme {
        Box(
            Modifier
                .width(390.dp)
                .background(CassiniTheme.colors.surface)
                .padding(horizontal = 16.dp),
        ) {
            CassiniSlider(positionMs = 704_000, durationMs = 1_430_000, onSeek = {})
        }
    }
}
