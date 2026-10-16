// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 6. The file
// is named after its composable, not after the TrackHeight enum it also holds.
@file:Suppress("FunctionNaming", "MagicNumber", "MatchingDeclarationName")

package org.cassini.ui.components

import android.os.SystemClock
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.cassini.ui.theme.CassiniTheme
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/**
 * The two progress tracks: [Thin] 4 dp (radius 2; accentTrack under accent: the Library banner) and [Thick] 6 dp
 * (radius 3; line under accent, the fill rounded too: the progress card).
 */
enum class TrackHeight(val height: Dp) { Thin(4.dp), Thick(6.dp) }

/**
 * A determinate track filled to [progress] (0–1, clamped). Decorative: the card around it says the percentage, so
 * the track adds no semantics of its own.
 *
 * When [moving], the fill is drawn in the draw phase: it glides to a new [progress] and, with an [anchor], moves at
 * the measured pace between two updates. The screen does not recompose for this motion. On the first frame, and with
 * no change, it draws [progress] exactly, as the static track does. With reduced motion, it goes to [progress] at once.
 */
@Composable
@Suppress("LongParameterList") // The static track's parameters, and the two of the moving one.
fun LinearTrack(
    progress: Float,
    modifier: Modifier = Modifier,
    height: TrackHeight = TrackHeight.Thin,
    trackColor: Color = Color.Unspecified,
    fillColor: Color = Color.Unspecified,
    moving: Boolean = false,
    anchor: TrackAnchor? = null,
) {
    val c = CassiniTheme.colors
    val thin = height == TrackHeight.Thin
    val shape = if (thin) CassiniTheme.shapes.track4 else CassiniTheme.shapes.track6
    val track = if (trackColor != Color.Unspecified) trackColor else if (thin) c.accentTrack else c.line
    val fill = if (fillColor != Color.Unspecified) fillColor else c.accent
    if (moving) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(height.height)
                .clip(shape)
                .background(track)
                .then(MovingFill(progress.coerceIn(0f, 1f), anchor, fill, rounded = !thin)),
        )
        return
    }
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

/** The fill of a moving [LinearTrack]. */
private data class MovingFill(
    val fraction: Float,
    val anchor: TrackAnchor?,
    val color: Color,
    val rounded: Boolean,
) : ModifierNodeElement<MovingFillNode>() {
    override fun create() = MovingFillNode(fraction, anchor, color, rounded)

    override fun update(node: MovingFillNode) = node.update(fraction, anchor, color, rounded)
}

/**
 * Draws the fill at [shown] and moves it with [TrackMotion]. A glide asks for each frame. When the fill follows the
 * [anchor], it waits until the next whole pixel. Each draw reads only the fields below: no allocation per frame.
 */
private class MovingFillNode(
    private var fraction: Float,
    private var anchor: TrackAnchor?,
    private var color: Color,
    private var rounded: Boolean,
) : Modifier.Node(), DrawModifierNode, LayoutAwareModifierNode {
    private var shown = fraction
    private var widthPx = 0f
    private var motion: Job? = null
    private var frameNanos = 0L
    private var lastFrameNanos = -1L
    private var lastAim = Float.NaN

    // One lambda for every frame: no allocation per frame.
    private val onFrame: (Long) -> Unit = { frameNanos = it }

    override val shouldAutoInvalidate: Boolean get() = false

    override fun onAttach() = move()

    fun update(fraction: Float, anchor: TrackAnchor?, color: Color, rounded: Boolean) {
        val moved = fraction != this.fraction || anchor != this.anchor
        this.fraction = fraction
        this.anchor = anchor
        this.color = color
        this.rounded = rounded
        if (moved) {
            motion?.cancel()
            move()
        }
        invalidateDraw()
    }

    private fun move() {
        if (!isAttached || (anchor == null && shown == fraction)) return
        motion = coroutineScope.launch { run() }
    }

    private suspend fun run() {
        if ((coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f) == 0f) {
            // Reduced motion: the published value at once, with no prediction.
            shown = fraction
            invalidateDraw()
            return
        }
        lastFrameNanos = -1L
        // A new run glides from the shown value: its first frame has no earlier aim.
        lastAim = Float.NaN
        while (true) {
            withFrameNanos(onFrame)
            // A frame clock that does not advance (a paused test clock) still counts one frame per frame.
            val sinceLastMs = (frameNanos - lastFrameNanos) / NANOS_PER_MS
            val frameMs = if (lastFrameNanos < 0) 0L else sinceLastMs.coerceAtLeast(MIN_FRAME_MS)
            lastFrameNanos = frameNanos
            // The anchor is on the elapsedRealtime clock; the glide is on the frame clock.
            val now = SystemClock.elapsedRealtime()
            val anchor = anchor
            val aim = TrackMotion.aim(fraction, anchor, now)
            var next = TrackMotion.step(shown, aim, frameMs, previousAim = if (lastAim.isNaN()) aim else lastAim)
            lastAim = aim
            val gap = (aim - next) * widthPx
            // Within a pixel or so, the fill is where it should be: a glide is only for a jump.
            val settled = widthPx > 0f && gap < SNAP_PIXELS
            if (settled && gap >= 0f) next = aim
            if (pixels(next) != pixels(shown)) invalidateDraw()
            shown = next
            if (!settled) continue
            if (anchor == null || anchor.reachesAtMs(anchor.windowEndFraction) <= now) return
            // The fill follows the prediction: wait until it reaches the next whole pixel.
            val nextPixel = (pixels(next) + HALF_PIXEL) / widthPx
            delay((anchor.reachesAtMs(nextPixel) - now).coerceAtLeast(MIN_WAIT_MS))
            lastFrameNanos = -1L
        }
    }

    private fun pixels(value: Float): Int = (value.coerceIn(0f, 1f) * widthPx).roundToInt()

    // The width comes from the layout: a test without drawing still lets the motion end.
    override fun onRemeasured(size: IntSize) {
        widthPx = size.width.toFloat()
    }

    override fun ContentDrawScope.draw() {
        val width = pixels(shown)
        if (width > 0) {
            val fill = Size(width.toFloat(), size.height)
            // The fill grows from the start edge, as the static track's does: the right one in RTL.
            val start = Offset(if (layoutDirection == LayoutDirection.Rtl) size.width - width else 0f, 0f)
            if (rounded) {
                drawRoundRect(color, start, fill, CornerRadius(size.height / 2))
            } else {
                drawRect(color, start, fill)
            }
        }
        drawContent()
    }

    private companion object {
        const val HALF_PIXEL = 0.5f
        const val SNAP_PIXELS = 1.5f
        const val NANOS_PER_MS = 1_000_000L
        const val MIN_FRAME_MS = 8L
        const val MIN_WAIT_MS = 8L
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
