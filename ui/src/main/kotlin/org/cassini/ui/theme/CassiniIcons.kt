@file:Suppress("MagicNumber") // The path data and geometry of the design's SVG icons (docs/design/ui.md section 4.5).

package org.cassini.ui.theme

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.concurrent.ConcurrentHashMap

/** One element of a glyph, with the geometry the design's SVG gives it. */
@Immutable
internal sealed interface GlyphPart {
    /** A `<path d>`; [addPathNodes] parses it. */
    data class Path(val d: String) : GlyphPart

    /** A `<circle>`, drawn as two arcs. */
    data class Circle(val cx: Float, val cy: Float, val r: Float) : GlyphPart

    /** A `<rect>` with rounded corners. */
    data class Rect(val x: Float, val y: Float, val width: Float, val height: Float, val rx: Float) : GlyphPart

    /** An `<ellipse>` rotated by [rotate] degrees about its centre, drawn as two arcs in a rotated group. */
    data class Ellipse(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val rotate: Float) : GlyphPart
}

private fun paths(vararg d: String): List<GlyphPart> = d.map { GlyphPart.Path(it) }

private fun circle(cx: Float, cy: Float, r: Float) = GlyphPart.Circle(cx, cy, r)

/** Three dots of radius [r] in a column (the More glyphs). */
private fun dots(r: Float): List<GlyphPart> = listOf(5f, 12f, 19f).map { circle(12f, it, r) }

/**
 * Every icon of the design (docs/design/ui.md section 4.5), from the SVG elements in design/reference/markup.
 * Stroked glyphs use round caps and joins and take the stroke width, in [viewport] units, from the caller; [filled]
 * glyphs ignore it. Draw one with [CassiniIcon]; the board sizes and strokes are in the ui.md table.
 */
enum class Glyph(internal val parts: List<GlyphPart>, val filled: Boolean = false, val viewport: Float = 24f) {
    /** 28/1.6 ink (Library header); 56/1.3 accent (Model download). */
    Logo(listOf(circle(14f, 14f, 5.5f), GlyphPart.Ellipse(14f, 14f, 12f, 4.2f, rotate = -20f)), viewport = 28f),
    Trash(paths("M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13")),
    Settings(
        listOf(circle(12f, 12f, 3f)) + paths(
            "M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21" +
                "a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1" +
                "a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8" +
                "l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1" +
                "a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9" +
                "a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z",
        ),
    ),
    Search(listOf(circle(11f, 11f, 7f)) + paths("m20 20-3.5-3.5")),
    Shield(paths("M12 3 4 6v6c0 4.5 3.4 8 8 9 4.6-1 8-4.5 8-9V6z")),
    Import(paths("M12 4v11M7 10l5 5 5-5M5 20h14")),
    ChevronDown(paths("m6 9 6 6 6-6")),
    ChevronRight(paths("m9 6 6 6-6 6")),

    /** No board: the in-note search header (previous match). */
    ChevronUp(paths("m6 15 6-6 6 6")),
    Back(paths("M15 6l-6 6 6 6")),
    Close(paths("M6 6l12 12M18 6 6 18")),
    Pencil(paths("M4 20h4L19 9l-4-4L4 16z")),
    List(paths("M3 6h18M3 12h18M3 18h12")),
    Pause(listOf(GlyphPart.Rect(6f, 5f, 4f, 14f, 1f), GlyphPart.Rect(14f, 5f, 4f, 14f, 1f)), filled = true),
    Play(paths("M7 5v14l12-7z"), filled = true),

    /** The More dots at 22 dp (r 1.7). */
    More(dots(1.7f), filled = true),

    /** The smaller More dots of the Saved voices options at 20 dp (r 1.6). */
    MoreSmall(dots(1.6f), filled = true),
    Check(paths("m5 12 5 5 9-10")),
    Back10(paths("M4 12a8 8 0 1 0 2.3-5.6", "M4 4v4h4")),

    /** Forward 10 s in the player; also Retranscribe in the note actions sheet. */
    Fwd10(paths("M20 12a8 8 0 1 1-2.3-5.6", "M20 4v4h-4")),
    Music(paths("M9 18V5l11-2v13") + listOf(circle(6f, 18f, 3f), circle(17f, 16f, 3f))),
    TextFile(paths("M6 3h9l4 4v14H6z", "M9 11h7M9 15h7")),
    Subtitles(listOf(GlyphPart.Rect(3f, 5f, 18f, 14f, 2f)) + paths("M7 14h4M13 14h4M7 10h10")),
    Info(listOf(circle(12f, 12f, 9f)) + paths("M12 11v6M12 7.5v.5")),

    /** Android's share sign: three nodes joined by two lines (the actions sheet's Share buttons). */
    Share(
        listOf(circle(18f, 5f, 3f), circle(6f, 12f, 3f), circle(18f, 19f, 3f)) +
            paths("M8.6 13.5l6.8 4", "M15.4 6.5l-6.8 4"),
    ),

    /** No board: the Warn and Error trust chips. */
    Warn(paths("M12 4 3 20h18z", "M12 10v4M12 17v.5")),
    ;

    companion object {
        /** The Retranscribe action uses the Forward 10 s arrow. */
        val Retranscribe: Glyph get() = Fwd10
    }
}

/** Builds and caches the [ImageVector] of each (glyph, stroke) pair. */
object CassiniIcons {
    private data class Key(val glyph: Glyph, val stroke: Float)

    private val cache = ConcurrentHashMap<Key, ImageVector>()

    /** The vector of [glyph] stroked at [stroke] viewport units (ignored for filled glyphs), in black: tint it. */
    fun vector(glyph: Glyph, stroke: Float): ImageVector {
        require(glyph.filled || stroke > 0f) { "$glyph is stroked: pass its board stroke width" }
        val key = Key(glyph, if (glyph.filled) 0f else stroke)
        return cache.getOrPut(key) { build(glyph, key.stroke) }
    }

    private fun build(glyph: Glyph, stroke: Float): ImageVector {
        val size = glyph.viewport.dp
        return ImageVector.Builder(glyph.name, size, size, glyph.viewport, glyph.viewport).apply {
            glyph.parts.forEach { part ->
                if (part is GlyphPart.Ellipse && part.rotate != 0f) {
                    group(rotate = part.rotate, pivotX = part.cx, pivotY = part.cy) {
                        addPart(part.nodes(), glyph, stroke)
                    }
                } else {
                    addPart(part.nodes(), glyph, stroke)
                }
            }
        }.build()
    }

    private fun ImageVector.Builder.addPart(nodes: List<PathNode>, glyph: Glyph, stroke: Float) {
        if (glyph.filled) {
            addPath(nodes, fill = SolidColor(Color.Black))
        } else {
            addPath(
                nodes,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = stroke,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }
}

/** The path of one part; circles and ellipses are two arcs, rects four lines and four corner arcs. */
internal fun GlyphPart.nodes(): List<PathNode> = when (this) {
    is GlyphPart.Path -> addPathNodes(d)
    is GlyphPart.Circle -> ellipseNodes(cx, cy, r, r)
    is GlyphPart.Ellipse -> ellipseNodes(cx, cy, rx, ry)
    is GlyphPart.Rect -> PathBuilder().apply {
        moveTo(x + rx, y)
        horizontalLineToRelative(width - 2 * rx)
        arcToRelative(rx, rx, 0f, isMoreThanHalf = false, isPositiveArc = true, dx1 = rx, dy1 = rx)
        verticalLineToRelative(height - 2 * rx)
        arcToRelative(rx, rx, 0f, isMoreThanHalf = false, isPositiveArc = true, dx1 = -rx, dy1 = rx)
        horizontalLineToRelative(-(width - 2 * rx))
        arcToRelative(rx, rx, 0f, isMoreThanHalf = false, isPositiveArc = true, dx1 = -rx, dy1 = -rx)
        verticalLineToRelative(-(height - 2 * rx))
        arcToRelative(rx, rx, 0f, isMoreThanHalf = false, isPositiveArc = true, dx1 = rx, dy1 = -rx)
        close()
    }.nodes
}

private fun ellipseNodes(cx: Float, cy: Float, rx: Float, ry: Float) = PathBuilder().apply {
    moveTo(cx - rx, cy)
    arcToRelative(rx, ry, 0f, isMoreThanHalf = true, isPositiveArc = false, dx1 = 2 * rx, dy1 = 0f)
    arcToRelative(rx, ry, 0f, isMoreThanHalf = true, isPositiveArc = false, dx1 = -2 * rx, dy1 = 0f)
    close()
}.nodes

/**
 * Draws [glyph] at [size] dp, stroked at [stroke] viewport units (any value for filled glyphs, for example 0f) and
 * tinted with [tint]. [contentDescription] is null for decorative icons; icon buttons give their board aria-label.
 */
@Composable
@Suppress(
    "FunctionNaming", // Composables are nouns in PascalCase.
    "LongParameterList", // The signature of docs/design/ui.md section 4.5 plus the modifier.
)
fun CassiniIcon(
    glyph: Glyph,
    size: Dp,
    stroke: Float,
    tint: Color,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    Icon(
        painter = rememberVectorPainter(CassiniIcons.vector(glyph, stroke)),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        tint = tint,
    )
}
