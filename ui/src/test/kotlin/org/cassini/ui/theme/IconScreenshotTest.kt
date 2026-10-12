package org.cassini.ui.theme

import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.cassini.ui.BoardTest
import org.cassini.ui.R
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * Every glyph at its board sizes and strokes (components/icons-light.png, icons-dark.png), each followed by a 48 dp
 * enlargement, and the launcher and notification icons from the XML drawables (components/launcher.png).
 */
@Config(qualifiers = "+h2400dp")
class IconScreenshotTest : BoardTest() {
    @Test fun iconsLight() = board("components", "icons-light") { GlyphStrip() }

    @Test
    @Config(qualifiers = "+night")
    fun iconsDark() = board("components", "icons-dark", dark = true) { GlyphStrip() }

    @Test fun launcher() = board("components", "launcher") { LauncherPreview() }

    @Composable
    private fun GlyphStrip() {
        val c = CassiniTheme.colors
        Column(
            Modifier
                .width(390.dp)
                .background(c.bg)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Caption("Glyph · board sizes (size/stroke; * = no board) · 48 dp")
            Glyph.entries.forEach { glyph -> GlyphRow(glyph, GlyphUses.getValue(glyph)) }
        }
    }

    @Composable
    private fun GlyphRow(glyph: Glyph, uses: List<GlyphUse>) {
        val c = CassiniTheme.colors
        Row(
            Modifier
                .fillMaxWidth()
                .background(c.surface, CassiniTheme.shapes.small)
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(glyph.name, Modifier.width(96.dp), style = CassiniTheme.type.caption, color = c.ink)
            uses.forEach { use ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val rec = use.tint.onRecording
                    Box(
                        Modifier
                            .size(maxOf(40, use.size + 8).dp)
                            .background(if (rec) CassiniTheme.recording.bg else c.bg, CassiniTheme.shapes.small),
                        contentAlignment = Alignment.Center,
                    ) {
                        CassiniIcon(glyph, use.size.dp, use.stroke, use.tint.color(), contentDescription = null)
                    }
                    Text(
                        use.label + if (use.onBoard) "" else "*",
                        style = CassiniTheme.type.tag12Plain,
                        color = c.text2,
                    )
                }
            }
            Box(Modifier.weight(1f))
            Box(
                Modifier
                    .size(56.dp)
                    .border(1.dp, c.line, CassiniTheme.shapes.small),
                contentAlignment = Alignment.Center,
            ) {
                CassiniIcon(glyph, 48.dp, uses.first().stroke, c.ink, contentDescription = null)
            }
        }
    }

    @Composable
    private fun LauncherPreview() {
        val resources = LocalResources.current
        Column(
            Modifier
                .width(390.dp)
                .background(CassiniTheme.colors.boardBg)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Caption("Layers on the 108 dp canvas (x1.25): 72 dp mask square (blue), 66 dp safe zone (red)")
            AdaptiveIcons(
                resources.getDrawable(R.mipmap.ui_ic_launcher, null) as AdaptiveIconDrawable,
                resources.getDrawable(R.mipmap.ui_ic_launcher_round, null) as AdaptiveIconDrawable,
            )
            Caption("Notification small icon (ui_ic_stat_logo) at 24 and 48 dp, white and tinted")
            val stat = resources.getDrawable(R.drawable.ui_ic_stat_logo, null)
            val tinted = checkNotNull(stat.constantState).newDrawable().mutate().apply {
                setTint(LightColors.text2.toArgb())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tile(CassiniTheme.recording.bg) {
                    Canvas(Modifier.size(24.dp)) { drawDrawable(stat) }
                    Canvas(Modifier.size(48.dp)) { drawDrawable(stat) }
                }
                Tile(LightColors.surface) {
                    Canvas(Modifier.size(24.dp)) { drawDrawable(tinted) }
                    Canvas(Modifier.size(48.dp)) { drawDrawable(tinted) }
                }
            }
            Caption("ui_ic_logo (28, 56) beside Glyph.Logo (28/1.6, 56/1.6)")
            val logo = resources.getDrawable(R.drawable.ui_ic_logo, null)
            Tile(CassiniTheme.colors.bg) {
                Canvas(Modifier.size(28.dp)) { drawDrawable(logo) }
                CassiniIcon(Glyph.Logo, 28.dp, 1.6f, CassiniTheme.colors.ink, contentDescription = null)
                Canvas(Modifier.size(56.dp)) { drawDrawable(logo) }
                CassiniIcon(Glyph.Logo, 56.dp, 1.6f, CassiniTheme.colors.ink, contentDescription = null)
            }
        }
    }

    /** The two layers with the mask and safe-zone guides, the system-masked icons and three launcher masks. */
    @Composable
    private fun AdaptiveIcons(adaptive: AdaptiveIconDrawable, round: AdaptiveIconDrawable) {
        val c = CassiniTheme.colors
        val background = checkNotNull(adaptive.background)
        val foreground = checkNotNull(adaptive.foreground)
        val monochrome = checkNotNull(adaptive.monochrome).mutate().apply { setTint(c.accentInk.toArgb()) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(135.dp)) {
                drawDrawable(background)
                drawDrawable(foreground)
                val maskSide = size.width * MASK
                drawRect(
                    c.accent,
                    topLeft = Offset((size.width - maskSide) / 2, (size.height - maskSide) / 2),
                    size = Size(maskSide, maskSide),
                    style = Stroke(1.dp.toPx()),
                )
                drawCircle(c.red, radius = size.width * SAFE / 2, style = Stroke(1.dp.toPx()))
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Caption("System mask: launcher, round")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Canvas(Modifier.size(56.dp)) { drawDrawable(adaptive) }
                    Canvas(Modifier.size(56.dp)) { drawDrawable(round) }
                }
                Caption("Circle, squircle, themed")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MaskedIcon(56.dp, rounded = false, background, foreground)
                    MaskedIcon(56.dp, rounded = true, background, foreground)
                    MaskedIcon(56.dp, rounded = false, monochrome, fill = c.accentTint)
                }
            }
        }
    }

    @Composable
    private fun Tile(color: Color, content: @Composable () -> Unit) {
        Row(
            Modifier
                .background(color, CassiniTheme.shapes.small)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { content() }
    }

    /**
     * The middle 72/108 of the [layers], drawn on the 108 dp canvas scaled so the mask is [visible] wide, clipped to a
     * circle or a rounded square as a launcher does. [fill] paints under the layers (the themed icon background).
     */
    @Composable
    private fun MaskedIcon(visible: Dp, rounded: Boolean, vararg layers: Drawable, fill: Color = Color.Transparent) {
        Canvas(Modifier.size(visible)) {
            val side = size.width
            val mask = Path().apply {
                if (rounded) {
                    addRoundRect(RoundRect(0f, 0f, side, side, CornerRadius(side * SQUIRCLE_RADIUS)))
                } else {
                    addOval(Rect(0f, 0f, side, side))
                }
            }
            clipPath(mask) {
                drawRect(fill)
                val inset = ((side / MASK - side) / 2).toInt()
                drawIntoCanvas { canvas ->
                    layers.forEach {
                        it.setBounds(-inset, -inset, side.toInt() + inset, side.toInt() + inset)
                        it.draw(canvas.nativeCanvas)
                    }
                }
            }
        }
    }

    @Composable
    private fun Caption(text: String) {
        Text(text, style = CassiniTheme.type.caption, color = CassiniTheme.colors.text2)
    }

    private fun DrawScope.drawDrawable(drawable: Drawable) {
        drawIntoCanvas { canvas ->
            drawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
            drawable.draw(canvas.nativeCanvas)
        }
    }

    private companion object {
        const val MASK = 72f / 108f
        const val SAFE = 66f / 108f
        const val SQUIRCLE_RADIUS = 0.25f
    }
}
