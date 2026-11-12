package io.codemyriad.cassini.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/** W1.0a freezes today's hues while L-14/L-17 are open; accent/status changes must not recolour people. */
class SpeakerPaletteTest {
    @Test fun lightSpeakerValuesStayFixed() {
        checkPalette(
            LightColors,
            listOf(
                SpeakerColors(Color(0xFF2346C8), Color(0xFF1A3596), Color(0xFFE8EDFC)),
                SpeakerColors(Color(0xFF0F766E), Color(0xFF0B5A54), Color(0xFFE3F2EF)),
                SpeakerColors(Color(0xFF5B2BB5), Color(0xFF5B2BB5), Color(0xFFEFEAFB)),
                SpeakerColors(Color(0xFFB45309), Color(0xFF8A3B07), Color(0xFFFCEFE3)),
            ),
            Color(0xFFFCF6EE),
        )
    }

    @Test fun darkSpeakerValuesStayFixed() {
        checkPalette(
            DarkColors,
            listOf(
                SpeakerColors(Color(0xFF8FA6FF), Color(0xFFB9C7FF), Color(0xFF1C2647)),
                SpeakerColors(Color(0xFF4FC7B8), Color(0xFF8EDDD2), Color(0xFF12302D)),
                SpeakerColors(Color(0xFFC1A8FF), Color(0xFFC1A8FF), Color(0xFF2A2045)),
                SpeakerColors(Color(0xFFF0A35E), Color(0xFFF7C291), Color(0xFF3A2614)),
            ),
            Color(0xFF2C2118),
        )
    }

    private fun checkPalette(colors: CassiniColors, expected: List<SpeakerColors>, wash: Color) {
        assertEquals(expected, SpeakerTone.entries.map(colors::speaker))
        assertEquals(wash, colors.speakerQuestionWash)
        val changed = colors.withStatusColorsChanged()
        assertEquals(
            "Speaker/avatar roles must not follow status or accent roles",
            expected,
            SpeakerTone.entries.map(changed::speaker),
        )
        assertEquals(wash, changed.speakerQuestionWash)
        // Prove the fixture actually changed every generic family that speakers previously used.
        listOf(Tone.Info, Tone.Ok, Tone.Warn).forEach { tone ->
            assertEquals(ToneColors(Color.Magenta, Color.Magenta, Color.Magenta), changed.tone(tone))
        }
        assertEquals(Color.Magenta, changed.violet)
        assertEquals(Color.Magenta, changed.violetTint)
        assertEquals(Color.Magenta, changed.amberWash)
    }

    // Explicit copy keeps production CassiniColors immutable without adding a mutation API for a test.
    @Suppress("LongMethod") // One argument per palette field.
    private fun CassiniColors.withStatusColorsChanged() = CassiniColors(
        isDark = isDark,
        ink = ink,
        bg = bg,
        surface = surface,
        surfaceWash = surfaceWash,
        boardBg = boardBg,
        text2 = text2,
        text3 = text3,
        text4 = text4,
        line = line,
        line2 = line2,
        line3 = line3,
        accent = Color.Magenta,
        accentFill = accentFill,
        onAccentFill = onAccentFill,
        accentInk = Color.Magenta,
        accentTint = Color.Magenta,
        accentTrack = accentTrack,
        teal = Color.Magenta,
        tealInk = Color.Magenta,
        tealTint = Color.Magenta,
        amber = Color.Magenta,
        amberInk = Color.Magenta,
        amberTint = Color.Magenta,
        amberWash = Color.Magenta,
        speakerBlue = speakerBlue,
        speakerTeal = speakerTeal,
        speakerViolet = speakerViolet,
        speakerAmber = speakerAmber,
        speakerQuestionWash = speakerQuestionWash,
        red = red,
        redInk = redInk,
        redTint = redTint,
        redLine = redLine,
        onRed = onRed,
        violet = Color.Magenta,
        violetTint = Color.Magenta,
        inkFill = inkFill,
        onInkFill = onInkFill,
        snackbarBg = snackbarBg,
        snackbarText = snackbarText,
        snackbarAction = snackbarAction,
        scrim = scrim,
        link = link,
    )
}
