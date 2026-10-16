// Animation constants and coordinate interpolation follow the Material container-morph spec.
@file:Suppress("MagicNumber")

package org.cassini.android.nav

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import org.cassini.ui.components.LocalNoteBounds
import org.cassini.ui.components.NoteBounds
import org.cassini.ui.theme.CassiniTheme
import kotlin.math.min

private const val MORPH_MS = 250
private const val CROSSFADE_END = 0.35f
private const val BOTTOM_FEATHER_RAMP = 0.10f
private const val FEATHER_UNIT_PX = 100f
private val RowCornerRadius = 14.dp
private val TopBarTitleOffset = 10.dp
private val BottomFeatherMax = 180.dp

/** True while a Note destination is actively animating its enter or exit transition with the Library. */
@Composable
internal fun isNoteTransitionRunning(withLibrary: Boolean): Boolean {
    if (!withLibrary) return false
    val transition = LocalNavAnimatedContentScope.current.transition
    return transition.currentState != transition.targetState
}

/** Remembers a [NoteBounds] registry for [CassiniApp] so Library rows can report their root bounds. */
@Composable
internal fun rememberNoteBounds(): NoteBounds = remember { MapNoteBounds() }

private class MapNoteBounds : NoteBounds {
    private val bounds = mutableStateMapOf<String, Rect>()
    private val layers = HashMap<String, GraphicsLayer>()

    override fun update(noteId: String, boundsInRoot: Rect, layer: GraphicsLayer?) {
        if (bounds[noteId] != boundsInRoot) {
            bounds[noteId] = boundsInRoot
        }
        if (layer != null) {
            layers[noteId] = layer
        }
    }

    override fun dispose(noteId: String) {
        bounds.remove(noteId)
        layers.remove(noteId)
    }

    override fun get(noteId: String): Rect? = bounds[noteId]

    override fun layer(noteId: String): GraphicsLayer? = layers[noteId]
}

private fun Scene<*>.isLibraryScene(): Boolean =
    entries.lastOrNull()?.contentKey?.toString()?.startsWith("LibraryKey") == true

private fun Scene<*>.noteIdOrNull(): String? =
    entries.lastOrNull()?.contentKey?.toString()
        ?.takeIf { it.startsWith("NoteKey(") }
        ?.substringAfter("noteId=")
        ?.substringBefore(",")

/**
 * Which note is opening from, or closing into, the Library under it. [appPush] and [appPop] set it when a transition
 * starts, and [noteContainerTransition] reads it while it draws: it morphs or slides that note over the Library only,
 * not a note that comes back from a screen above it.
 */
internal class NoteMorph {
    var noteId: String? = null
}

/**
 * Push transition for [CassiniApp]. A [NoteKey] opened from [LibraryKey] enters as a `LibraryNoteScene` that already
 * holds the Library, so the scene enters with no animation on top of the Library (`targetContentZIndex = 1f`) and
 * [noteContainerTransition] animates the note alone: from its row when the row bounds are known, otherwise with the
 * slide of [push]. Every other push is the standard horizontal [push].
 */
internal fun <S : Scene<*>> AnimatedContentTransitionScope<S>.appPush(morph: NoteMorph): ContentTransform {
    val noteId = targetState.noteIdOrNull()?.takeIf { initialState.isLibraryScene() }
    morph.noteId = noteId
    if (noteId == null) return push()
    val hold = tween<Float>(MORPH_MS, easing = FastOutSlowInEasing)
    // No fadeIn: noteContainerTransition already starts the note at alpha 0. A fadeIn draws the first frame of the
    // whole screen at alpha 0 through a full-size offscreen buffer, which stalled that frame by about 100 ms.
    return (
        EnterTransition.None togetherWith fadeOut(hold, targetAlpha = 1f)
        ).apply {
        targetContentZIndex = 1f
    }
}

/**
 * Pop (and predictive-pop) transition for [CassiniApp]: when closing a [NoteKey] back to [LibraryKey], the exiting
 * `LibraryNoteScene` stays on top (`targetContentZIndex = -1f`) with the Library inside it, while
 * [noteContainerTransition] stretches the note back into its row, or slides it out as [pop] does when the row bounds
 * are unknown. Otherwise uses the standard horizontal [pop].
 */
internal fun <S : Scene<*>> AnimatedContentTransitionScope<S>.appPop(morph: NoteMorph): ContentTransform {
    val noteId = initialState.noteIdOrNull()?.takeIf { targetState.isLibraryScene() }
    morph.noteId = noteId
    if (noteId == null) return pop()
    val hold = tween<Float>(MORPH_MS, easing = FastOutSlowInEasing)
    return (
        EnterTransition.None togetherWith fadeOut(hold, targetAlpha = 1f)
        ).apply {
        targetContentZIndex = -1f
    }
}

private class MorphSpec(
    val maxCornerPx: Float,
    val bottomFeatherMaxPx: Float,
    val strokePx: Float,
    val headerShiftPx: Float,
    val colors: org.cassini.ui.theme.CassiniColors,
    val cardLayer: GraphicsLayer,
) {
    val featherBrush = Brush.verticalGradient(
        0f to Color.Transparent,
        0.65f to colors.bg,
        1f to colors.bg,
        startY = 0f,
        endY = FEATHER_UNIT_PX,
    )
}

@Composable
private fun rememberMorphSpec(): MorphSpec {
    val density = LocalDensity.current
    val topInsetDp = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
    val maxCornerPx = with(density) { RowCornerRadius.toPx() }
    val bottomFeatherMaxPx = with(density) { BottomFeatherMax.toPx() }
    val strokePx = with(density) { 1.dp.toPx() }
    val headerShiftPx = with(density) { (topInsetDp + TopBarTitleOffset).toPx() }
    val colors = CassiniTheme.colors
    val cardLayer = rememberGraphicsLayer()
    return remember(maxCornerPx, bottomFeatherMaxPx, strokePx, headerShiftPx, colors, cardLayer) {
        MorphSpec(maxCornerPx, bottomFeatherMaxPx, strokePx, headerShiftPx, colors, cardLayer)
    }
}

/**
 * Stretches the Note screen container from its Library row bounds on open and back into its row bounds on close (both
 * button/system back and predictive swipe-back) using hardware RenderNode layers and outline clipping. Without row
 * bounds it slides and fades the note as [push] and [pop] do, over the Library that stays still under it.
 */
@Composable
internal fun Modifier.noteContainerTransition(noteId: String, morph: NoteMorph): Modifier {
    val navScope = LocalNavAnimatedContentScope.current
    val noteBounds = LocalNoteBounds.current
    val fraction by navScope.transition.animateFloat(
        transitionSpec = { tween(MORPH_MS, easing = FastOutSlowInEasing) },
        label = "NoteMorphFraction",
    ) { state ->
        when (state) {
            EnterExitState.PreEnter -> 0f
            EnterExitState.Visible -> 1f
            EnterExitState.PostExit -> 0f
        }
    }
    DisposableEffect(noteBounds, noteId) {
        onDispose {
            noteBounds?.layer(noteId)?.takeIf { !it.isReleased }?.alpha = 1f
        }
    }
    val spec = rememberMorphSpec()
    return this
        .drawWithContent {
            val f = fraction.coerceIn(0f, 1f)
            val rowRect = if (morph.noteId == noteId && f < 0.999f) noteBounds?.get(noteId) else null
            val rowLayer = noteBounds?.layer(noteId)?.takeIf { !it.isReleased }
            if (rowRect == null) {
                if (rowLayer != null && rowLayer.alpha != 1f) rowLayer.alpha = 1f
                drawContent()
            } else {
                drawMorphFrame(f, rowRect, rowLayer, spec)
            }
        }
        .graphicsLayer {
            val f = fraction.coerceIn(0f, 1f)
            val overLibrary = morph.noteId == noteId && f < 0.999f
            val rowRect = if (overLibrary) noteBounds?.get(noteId) else null
            if (rowRect != null) {
                val currentRect = lerp(rowRect, Rect(0f, 0f, size.width, size.height), f)
                val scale = (currentRect.width / size.width.coerceAtLeast(1f)).coerceAtLeast(0.01f)
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = scale
                scaleY = scale
                translationX = currentRect.left
                translationY = currentRect.top - spec.headerShiftPx * scale * (1f - f)
                alpha = (f / CROSSFADE_END).coerceIn(0f, 1f)
                // Fade each draw instead of the whole screen through a full-size offscreen buffer: allocating and
                // clearing that buffer stalled the first frame of the open by about 100 ms on a Pixel 8.
                compositingStrategy = CompositingStrategy.ModulateAlpha
            } else if (overLibrary) {
                // The slide of push() and pop(), on the note alone.
                translationX = size.width * SLIDE_FRACTION * (1f - f)
                alpha = f
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
        }
}

private fun ContentDrawScope.drawMorphFrame(
    f: Float,
    rowRect: Rect,
    rowLayer: GraphicsLayer?,
    spec: MorphSpec,
) {
    drawRect(color = spec.colors.scrim.copy(alpha = 0.35f * f))
    val currentRect = lerp(rowRect, Rect(0f, 0f, size.width, size.height), f)
    val cornerPx = spec.maxCornerPx * (1f - f)
    val cardLayer = spec.cardLayer
    cardLayer.setRoundRectOutline(currentRect.topLeft, currentRect.size, cornerPx)
    cardLayer.clip = true
    cardLayer.record {
        drawRect(color = spec.colors.bg, topLeft = currentRect.topLeft, size = currentRect.size)
        this@drawMorphFrame.drawContent()
        val edgeProgress = ((1f - f) / BOTTOM_FEATHER_RAMP).coerceIn(0f, 1f)
        val fadeHeight = min(spec.bottomFeatherMaxPx * edgeProgress, currentRect.height * 0.55f)
        if (fadeHeight > 1f) {
            val fadeTop = currentRect.bottom - fadeHeight
            withTransform({
                translate(left = currentRect.left, top = fadeTop)
                scale(scaleX = 1f, scaleY = fadeHeight / FEATHER_UNIT_PX, pivot = Offset.Zero)
            }) {
                drawRect(brush = spec.featherBrush, size = Size(currentRect.width, FEATHER_UNIT_PX))
            }
        }
        drawRowOverlay(f, rowRect, currentRect, rowLayer)
    }
    drawLayer(cardLayer)
    val borderAlpha = (4f * f * (1f - f)).coerceIn(0f, 1f)
    if (borderAlpha > 0.005f) {
        drawRoundRect(
            color = spec.colors.line.copy(alpha = borderAlpha),
            topLeft = currentRect.topLeft,
            size = currentRect.size,
            cornerRadius = CornerRadius(cornerPx, cornerPx),
            style = Stroke(width = spec.strokePx),
        )
    }
}

private fun DrawScope.drawRowOverlay(
    f: Float,
    rowRect: Rect,
    currentRect: Rect,
    rowLayer: GraphicsLayer?,
) {
    if (rowLayer == null) return
    val rowAlpha = (1f - f / CROSSFADE_END).coerceIn(0f, 1f)
    if (rowAlpha <= 0.005f) {
        if (rowLayer.alpha != 1f) rowLayer.alpha = 1f
        return
    }
    rowLayer.alpha = rowAlpha
    val rowScale = (currentRect.width / rowRect.width.coerceAtLeast(1f)).coerceAtLeast(0.01f)
    withTransform({
        translate(left = currentRect.left, top = currentRect.top)
        scale(scaleX = rowScale, scaleY = rowScale, pivot = Offset.Zero)
    }) {
        drawLayer(rowLayer)
    }
}
