package io.codemyriad.cassini.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SpeakerPaletteTest {
    @Test fun everyToneUsesItsOwnSpeakerAndAvatarRolesInBothModes() {
        val order = listOf(1, 2, 3, 4, 5, 6, 0)
        assertEquals(order.size, SpeakerTone.entries.size)
        for (colors in listOf(LightColors, DarkColors)) {
            val values = if (colors.isDark) BrandTokenValues.dark else BrandTokenValues.light
            for ((tone, slot) in SpeakerTone.entries.zip(order)) {
                val expected = SpeakerColors(
                    values.getValue("speaker-$slot"),
                    values.getValue("speaker-$slot"),
                    values.getValue("avatar-$slot"),
                )
                assertEquals("$tone/${colors.isDark}", expected, colors.speaker(tone))
                assertNotEquals("Speakers are not the app accent", colors.accent, expected.ink)
            }
            assertEquals(colors.avatar0, colors.speakerQuestionWash)
        }
    }
}
