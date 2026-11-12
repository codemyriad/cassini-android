package io.codemyriad.cassini.ui.theme

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
        text("ink on bg", 16.34, 15.53, { it.ink }, { it.bg }),
        text("ink on surface", 18.00, 14.43, { it.ink }, { it.surface }),
        text("text2 on bg", 6.78, 8.84, { it.text2 }, { it.bg }),
        text("text2 on surface", 7.47, 8.21, { it.text2 }, { it.surface }),
        text("text2 on boardBg", 6.20, 8.56, { it.text2 }, { it.boardBg }),
        text("text3 on bg", 6.78, 8.84, { it.text3 }, { it.bg }),
        text("text3 on accentTint", 5.95, 6.88, { it.text3 }, { it.accentTint }),
        text("text3 on boardBg", 6.20, 8.56, { it.text3 }, { it.boardBg }),
        text("text4 on sunken", 4.64, 5.28, { it.text4 }, { it.boardBg }),
        text("text4 on bg", 5.07, 5.45, { it.text4 }, { it.bg }),
        text("text4 on surface", 5.58, 5.06, { it.text4 }, { it.surface }),
        text("accentInk on accentTint", 5.40, 8.59, { it.accentInk }, { it.accentTint }),
        text("tealInk on tealTint", 4.73, 6.20, { it.tealInk }, { it.tealTint }),
        text("tealInk on surface", 5.67, 7.46, { it.tealInk }, { it.surface }),
        text("amberInk on amberTint", 6.55, 8.58, { it.amberInk }, { it.amberTint }),
        text("amberInk on amberWash", 6.55, 8.58, { it.amberInk }, { it.amberWash }),
        text("amberInk on surface", 7.60, 10.39, { it.amberInk }, { it.surface }),
        text("redInk on redTint", 5.20, 6.13, { it.redInk }, { it.redTint }),
        text("redInk on surface", 6.11, 7.32, { it.redInk }, { it.surface }),
        text("redInk on bg", 5.55, 7.88, { it.redInk }, { it.bg }),
        text("onAccentFill on accentFill", 6.78, 11.05, { it.onAccentFill }, { it.accentFill }),
        text("accent on surface", 5.01, 10.26, { it.accent }, { it.surface }),
        text("white on red (Record)", 6.11, 6.11, white, { it.red }),
        text("onRed on red (Record)", 6.11, 6.11, { it.onRed }, { it.red }),
        text("onInkFill on inkFill", 16.34, 15.53, { it.onInkFill }, { it.inkFill }),
        text("snackbar action on snackbar", 10.26, 5.34, { it.snackbarAction }, { it.snackbarBg }),
        text("snackbar text on snackbar", 14.43, 15.53, { it.snackbarText }, { it.snackbarBg }),
        text("link on surface", 5.01, 10.26, { it.link }, { it.surface }),
        nonText("accent on accentTrack", 3.99, 8.59, { it.accent }, { it.accentTrack }),
        nonText("accent on line", 3.44, 8.20, { it.accent }, { it.line }),
        nonText("teal dot on surface", 5.67, 7.46, { it.teal }, { it.surface }),
        nonText("amber dot on surface", 7.60, 10.39, { it.amber }, { it.surface }),
        nonText("error dot on surface", 6.11, 7.32, { it.error }, { it.surface }),
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

    /** L-24: selected words and search hits keep text AA in both modes. */
    @Test fun highlightAndInkMeetTextContrast() {
        for (colors in listOf(LightColors, DarkColors)) {
            assertTrue(contrast(colors.highlightInk, colors.highlight) >= TEXT)
            assertTrue(contrast(colors.onRecord, colors.errorFill) >= TEXT)
            assertTrue(contrast(colors.onRecord, colors.successFill) >= TEXT)
            assertTrue(contrast(colors.recordInk, colors.recordTint) >= TEXT)
            assertTrue(contrast(colors.neutralInk, colors.neutralTint) >= TEXT)
            for (tone in SpeakerTone.entries) {
                val speaker = colors.speaker(tone)
                assertTrue("$tone on avatar", contrast(speaker.ink, speaker.tint) >= TEXT)
                assertTrue("$tone on ground", contrast(speaker.ink, colors.bg) >= TEXT)
            }
        }
    }

    @Test fun lightScrimUsesTheVendoredAlpha() {
        assertEquals(BrandTokenValues.light.getValue("scrim"), LightColors.scrim)
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
