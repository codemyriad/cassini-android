package org.cassini.ui.components

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.layer.GraphicsLayer

/**
 * Tracks the root bounds and recorded [GraphicsLayer] of visible Library note rows so `:app` navigation can morph a
 * note screen from and back into its place in the list. Null in previews and stateless screenshot tests.
 */
@Stable
interface NoteBounds {
    fun update(noteId: String, boundsInRoot: Rect, layer: GraphicsLayer? = null)
    fun dispose(noteId: String)
    fun get(noteId: String): Rect?
    fun layer(noteId: String): GraphicsLayer? = null
}

val LocalNoteBounds: ProvidableCompositionLocal<NoteBounds?> = staticCompositionLocalOf { null }

/**
 * True while a Note screen is actively running its enter transition so [TurnParagraph] defers creating hundreds of
 * per-word [androidx.compose.ui.text.LinkAnnotation.Clickable] child layout nodes until the animation finishes.
 */
val LocalDeferWordLinks: ProvidableCompositionLocal<Boolean> = compositionLocalOf { false }
