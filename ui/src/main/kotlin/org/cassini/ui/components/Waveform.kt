// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 6.
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.components

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.first
import org.cassini.ui.R
import org.cassini.ui.components.WaveformLevels.Companion.BAR_MS
import org.cassini.ui.components.WaveformLevels.Companion.SAMPLE_MS
import org.cassini.ui.theme.CassiniTheme
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** The tallest bar is this many dp at level 1. */
private const val MAX_BAR_DP = 92

/** The playhead runs at most this far past the captured time of the newest sample: two samples. */
private const val MAX_AHEAD_MS = 2 * SAMPLE_MS

/** A playhead this far from its target jumps to it (the first sample, a new recording). */
private const val SNAP_MS = 2 * BAR_MS

/** The time constant of the playhead correction. */
private const val PLAYHEAD_EASE_MS = 100.0

/** The time constant of the newest bar's height: a few frames. */
private const val HEIGHT_EASE_MS = 40.0

private const val NANOS_PER_MS = 1_000_000.0

/**
 * The Recording waveform (board 01): a 96 dp full-width canvas of [levels] (0–1, oldest first; 56 bars for the last
 * 7 s), 3 dp apart and equally wide. Each bar is max(4, round(level x 92)) dp tall, centred, with 2 dp corners; the
 * newest [freshCount] bars are rec.barFresh, the others rec.barIdle. One image node, "Microphone level, last 7
 * seconds".
 *
 * Not [running] (paused, previews, tests), it draws the bars in their places, the newest at the right edge. While
 * [running], the bars scroll at the display rate: each frame moves a playhead from the captured time of the newest
 * sample plus the time since it arrived, and only the draw phase reads it. The newest bar grows to its level over a
 * few frames. With reduced motion the waveform does not scroll.
 */
@Composable
fun Waveform(levels: WaveformLevels, modifier: Modifier = Modifier, running: Boolean = false, freshCount: Int = 7) {
    val rec = CassiniTheme.recording
    val description = stringResource(R.string.ui_recording_waveform)
    val motion = remember { WaveformMotion() }
    val latest by rememberUpdatedState(levels)
    if (running) {
        LaunchedEffect(motion) { motion.run { latest } }
    }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(96.dp)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Image
            },
    ) {
        val moving = if (running) motion.levels else null
        if (moving == null) {
            val bars = levels.bars
            drawBars(bars, 0f, bars.lastOrNull() ?: 0f, 0f, freshCount, rec.barFresh, rec.barIdle)
        } else {
            clipRect {
                drawBars(moving.bars, motion.offset, motion.newest, motion.older, freshCount, rec.barFresh, rec.barIdle)
            }
        }
    }
}

/**
 * Draws [bars] moved [offset] bars to the right of their places. The newest bar has the level [newest], the bar
 * before the oldest has [older], and the bars after the newest have none. Only the bars on the canvas are drawn.
 */
@Suppress("LongParameterList") // the frame values and the two colours, read once per draw
private fun DrawScope.drawBars(
    bars: List<Float>,
    offset: Float,
    newest: Float,
    older: Float,
    freshCount: Int,
    fresh: Color,
    idle: Color,
) {
    val count = bars.size
    if (count == 0) return
    val gap = 3.dp.toPx()
    val width = (size.width - (count - 1) * gap) / count
    val radius = CornerRadius(2.dp.toPx())
    for (index in floor(-offset).toInt() - 1..ceil(count - offset).toInt()) {
        val x = (index + offset) * (width + gap)
        if (x + width <= 0f || x >= size.width) continue
        val level = when {
            index == count - 1 -> newest
            index in 0 until count -> bars[index]
            index == -1 -> older
            else -> 0f
        }
        val height = maxOf(4, (level.coerceIn(0f, 1f) * MAX_BAR_DP).roundToInt()).dp.toPx()
        drawRoundRect(
            color = if (index >= count - freshCount) fresh else idle,
            topLeft = Offset(x, (size.height - height) / 2f),
            size = Size(width, height),
            cornerRadius = radius,
        )
    }
}

/**
 * The scroll of a running [Waveform]. The frame loop of [run] writes the states, and only the draw phase reads them,
 * so a frame redraws the canvas without a recomposition.
 *
 * The playhead is a captured time. It starts at the end of the newest bar, where the static picture has it, and stays
 * there until the next sample arrives. Then it follows the captured time of the newest sample plus the time since it
 * arrived, at most [MAX_AHEAD_MS] past it. It never moves back: when a sample is late, it waits.
 */
@Stable
internal class WaveformMotion {
    /** The bars that the loop placed; null draws the static picture. */
    var levels by mutableStateOf<WaveformLevels?>(null)
        private set

    /** How far the bars are to the right of their static places, in bars. */
    var offset by mutableFloatStateOf(0f)
        private set

    /** The level of the newest bar, eased. */
    var newest by mutableFloatStateOf(0f)
        private set

    /** The level of the bar that scrolled out before the oldest bar of [levels]. */
    var older by mutableFloatStateOf(0f)
        private set

    private var seen: WaveformLevels? = null
    private var playheadMs = 0.0
    private var capturedMs = 0L
    private var newestBar = 0L
    private var newestLevel = 0f
    private var olderLevel = 0f
    private var arrivedNanos = 0L
    private var frameNanos = 0L

    /** True once a sample arrived after the first one: only then does the playhead move. */
    private var moving = false

    /**
     * Moves the waveform each frame with the latest [source] until cancelled; then the static picture shows again.
     * With reduced motion, it shows the static picture and asks for no frames until motion is on again.
     */
    suspend fun run(source: () -> WaveformLevels) {
        val scale = coroutineContext[MotionDurationScale]
        val frame: (Long) -> Unit = { nanos -> step(source(), nanos) }
        try {
            while (true) {
                if (scale != null && scale.scaleFactor == 0f) {
                    stop()
                    // The scale factor of a window is a snapshot state.
                    snapshotFlow { scale.scaleFactor }.first { it != 0f }
                }
                withInfiniteAnimationFrameNanos(frame)
            }
        } finally {
            stop()
        }
    }

    /** Shows the static picture. The next [step] starts again from it. */
    fun stop() {
        seen = null
        levels = null
    }

    /** Moves the waveform to the frame time [nanos] with the latest bars [latest]. */
    fun step(latest: WaveformLevels, nanos: Long) {
        val shown = seen
        val dtMs = if (shown == null) 0.0 else (nanos - frameNanos) / NANOS_PER_MS
        frameNanos = nanos
        when {
            shown == null -> begin(latest)
            latest != shown -> arrive(shown, latest, nanos)
        }
        advance(dtMs)
    }

    /** Starts from the static picture: the playhead at the end of the newest bar, the heights as they are. */
    private fun begin(latest: WaveformLevels) {
        seen = latest
        capturedMs = latest.capturedMs
        newestBar = latest.capturedMs / BAR_MS
        playheadMs = ((newestBar + 1) * BAR_MS).toDouble()
        newestLevel = latest.bars.lastOrNull() ?: 0f
        olderLevel = 0f
        moving = false
    }

    /** A new sample: keeps the bar that scrolled out of [old], and starts a new newest bar at the minimum height. */
    private fun arrive(old: WaveformLevels, latest: WaveformLevels, nanos: Long) {
        val bar = latest.capturedMs / BAR_MS
        val shift = bar - newestBar
        if (latest.capturedMs < capturedMs) {
            // A new recording.
            begin(latest)
            return
        }
        if (shift > 0) {
            olderLevel = if (shift <= old.bars.size) old.bars[(shift - 1).toInt()] else 0f
            newestLevel = 0f
        }
        seen = latest
        capturedMs = latest.capturedMs
        newestBar = bar
        arrivedNanos = nanos
        moving = true
    }

    private fun advance(dtMs: Double) {
        if (moving) {
            val limit = capturedMs + MAX_AHEAD_MS.toDouble()
            val target = min(capturedMs + (frameNanos - arrivedNanos) / NANOS_PER_MS, limit)
            playheadMs = if (abs(target - playheadMs) > SNAP_MS) {
                target
            } else {
                // Runs at the speed of time, and closes the distance to the target a little each frame.
                val next = playheadMs + dtMs + (target - playheadMs - dtMs) * ease(dtMs, PLAYHEAD_EASE_MS)
                max(playheadMs, min(next, limit))
            }
        }
        val level = seen?.bars?.lastOrNull() ?: 0f
        newestLevel += ((level - newestLevel) * ease(dtMs, HEIGHT_EASE_MS)).toFloat()
        offset = (newestBar + 1 - playheadMs / BAR_MS).toFloat()
        newest = newestLevel
        older = olderLevel
        levels = seen
    }

    /** The part of the distance that an exponential ease with the time constant [tauMs] closes in [dtMs]. */
    private fun ease(dtMs: Double, tauMs: Double): Double = 1.0 - exp(-dtMs / tauMs)
}

/** The board's 56 fixture levels: 0.18 + 0.82 x |sin(0.61 i) x cos(0.23 i + 1.1)|. */
internal fun boardWaveformLevels(count: Int = WaveformLevels.BARS): WaveformLevels {
    val bars = List(count) { i -> (0.18 + 0.82 * abs(sin(i * 0.61) * cos(i * 0.23 + 1.1))).toFloat() }
    return WaveformLevels(bars.toImmutableList())
}

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
