package org.cassini.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * WCAG 2.x contrast of the token pairs in docs/design/ui.md section 11, on the plain JVM. Every pair must reach
 * 4.5 (text) or 3.0 (large text and non-text) in both themes, and the ratio printed in the table must match the
 * tokens to two decimals, so the table and the palette cannot drift apart.
 */
class ContrastTest {
    private class Pair(
        val label: String,
        val min: Double,
        val light: Double?,
        val dark: Double?,
        val fg: (CassiniColors) -> Color,
        val bg: (CassiniColors) -> Color,
    )

    private val white: (CassiniColors) -> Color = { Color.White }

    @Suppress("LongMethod") // One line per row of the table.
    private fun pairs(): List<Pair> = listOf(
        text("ink on bg", 16.63, 16.18, { it.ink }, { it.bg }),
        text("ink on surface", 17.99, null, { it.ink }, { it.surface }),
        text("text2 on bg", 7.33, null, { it.text2 }, { it.bg }),
        text("text2 on surface", 7.93, 7.83, { it.text2 }, { it.surface }),
        text("text2 on boardBg", 6.82, null, { it.text2 }, { it.boardBg }),
        text("text3 on bg", 9.35, null, { it.text3 }, { it.bg }),
        text("text3 on accentTint", 8.65, null, { it.text3 }, { it.accentTint }),
        text("text3 on boardBg", null, null, { it.text3 }, { it.boardBg }),
        text("text4 on bg", 4.55, 6.20, { it.text4 }, { it.bg }),
        text("text4 on surface", 4.93, null, { it.text4 }, { it.surface }),
        text("accentInk on accentTint", 9.00, 8.94, { it.accentInk }, { it.accentTint }),
        text("tealInk on tealTint", 6.99, 9.03, { it.tealInk }, { it.tealTint }),
        text("tealInk on surface", 8.06, null, { it.tealInk }, { it.surface }),
        text("amberInk on amberTint", 6.86, 8.91, { it.amberInk }, { it.amberTint }),
        text("amberInk on amberWash", 7.22, null, { it.amberInk }, { it.amberWash }),
        text("amberInk on surface", 7.75, null, { it.amberInk }, { it.surface }),
        text("redInk on redTint", 5.80, 8.51, { it.redInk }, { it.redTint }),
        text("redInk on surface", 6.92, null, { it.redInk }, { it.surface }),
        text("redInk on bg", 6.39, null, { it.redInk }, { it.bg }),
        text("violet on violetTint", 7.26, 7.41, { it.violet }, { it.violetTint }),
        text("white on accentFill", 7.55, null, white, { it.accentFill }),
        text("onAccentFill on accentFill", 7.55, null, { it.onAccentFill }, { it.accentFill }),
        text("accent on surface", 7.55, 7.40, { it.accent }, { it.surface }),
        text("white on red (Record)", 5.18, null, white, { it.red }),
        text("onRed on red (Record)", 5.18, null, { it.onRed }, { it.red }),
        text("onInkFill on inkFill", 17.99, null, { it.onInkFill }, { it.inkFill }),
        text("snackbar action on snackbar", 8.92, null, { it.snackbarAction }, { it.snackbarBg }),
        text("snackbar text on snackbar", null, null, { it.snackbarText }, { it.snackbarBg }),
        text("link on surface", null, null, { it.link }, { it.surface }),
        nonText("accent on accentTrack", 5.12, null, { it.accent }, { it.accentTrack }),
        nonText("accent on line", 5.97, null, { it.accent }, { it.line }),
        nonText("teal dot on surface", 5.47, null, { it.teal }, { it.surface }),
        nonText("amber dot on surface", 5.02, null, { it.amber }, { it.surface }),
        nonText("red dot on surface", 5.18, null, { it.red }, { it.surface }),
    )

    @Test fun lightPairsMeetTheirMinimumAndTheTable() = check(LightColors) { it.light }

    @Test fun darkPairsMeetTheirMinimumAndTheTable() = check(DarkColors) { it.dark }

    @Test fun recordingPairs() {
        val rec = CassiniRecordingColors
        listOf(
            Triple("rec.text", rec.text, 16.20),
            Triple("rec.text2", rec.text2, 8.22),
            Triple("rec.status", rec.status, 8.64),
            Triple("rec.dot", rec.dot, 6.30),
            Triple("rec.barFresh", rec.barFresh, 7.73),
        ).forEach { (label, fg, documented) ->
            val ratio = contrast(fg, rec.bg)
            assertTrue("$label on rec.bg is $ratio", ratio >= TEXT)
            assertEquals("$label on rec.bg", documented, ratio, TABLE_DELTA)
        }
        assertTrue("onPause on pauseFill", contrast(rec.onPause, rec.pauseFill) >= TEXT)
    }

    /** Accepted deviation 1: the board's #6B7480 fails AA on bg, which is why text4 is #687180. */
    @Test fun boardText4FailsAa() {
        val ratio = contrast(Color(BOARD_TEXT4), LightColors.bg)
        assertEquals(4.38, ratio, TABLE_DELTA)
        assertTrue(ratio < TEXT)
    }

    /** The light scrim over bg gives the boards' #8A9098 (docs/design/ui.md section 4.1). */
    @Test fun lightScrimOverBgIsTheBoardsGrey() {
        val s = LightColors.scrim
        val b = LightColors.bg
        fun over(fg: Float, bg: Float) = Math.round((fg * s.alpha + bg * (1 - s.alpha)) * CHANNEL_MAX)
        assertEquals(0x8A, over(s.red, b.red))
        assertEquals(0x90, over(s.green, b.green))
        assertEquals(0x98, over(s.blue, b.blue))
    }

    private fun check(colors: CassiniColors, documented: (Pair) -> Double?) {
        val failures = pairs().mapNotNull { pair ->
            val ratio = contrast(pair.fg(colors), pair.bg(colors))
            val expected = documented(pair)
            when {
                ratio < pair.min -> "${pair.label}: %.2f < %.1f".format(ratio, pair.min)
                expected != null && kotlin.math.abs(ratio - expected) > TABLE_DELTA ->
                    "${pair.label}: %.2f, the table says %.2f".format(ratio, expected)
                else -> null
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun text(label: String, light: Double?, dark: Double?, fg: (CassiniColors) -> Color, bg: (CassiniColors) -> Color) =
        Pair(label, TEXT, light, dark, fg, bg)

    private fun nonText(label: String, light: Double?, dark: Double?, fg: (CassiniColors) -> Color, bg: (CassiniColors) -> Color) =
        Pair(label, NON_TEXT, light, dark, fg, bg)

    private companion object {
        const val TEXT = 4.5
        const val NON_TEXT = 3.0
        const val TABLE_DELTA = 0.0051
        const val CHANNEL_MAX = 255f
        const val BOARD_TEXT4 = 0xFF6B7480

        /** WCAG 2.x relative luminance of an opaque sRGB colour. */
        fun luminance(c: Color): Double {
            fun channel(v: Float): Double = if (v <= 0.04045f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
            return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
        }

        fun contrast(a: Color, b: Color): Double {
            require(a.alpha == 1f && b.alpha == 1f) { "contrast needs opaque colours" }
            val la = luminance(a)
            val lb = luminance(b)
            return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
        }
    }
}
