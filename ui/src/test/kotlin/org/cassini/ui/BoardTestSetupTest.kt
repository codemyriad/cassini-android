package org.cassini.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.cassini.ui.format.FIXED_ZONE
import org.cassini.ui.format.FormatSettings
import org.cassini.ui.format.LocalFormat
import org.cassini.ui.theme.CassiniTheme
import org.cassini.ui.theme.DarkColors
import org.cassini.ui.theme.LightColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.util.Locale

/** The pinned inputs of [BoardTest]: format, theme, window colour, and the locale and font-scale overrides. */
class BoardTestSetupTest : BoardTest() {
    @Test fun pinsFormatAndLightTheme() {
        var format: FormatSettings? = null
        var dark = true
        var windowBg = 0L
        show {
            format = LocalFormat.current
            dark = CassiniTheme.colors.isDark
            windowBg = colorResource(R.color.ui_window_bg).value.toLong()
        }
        assertEquals(FormatSettings(Locale.ENGLISH, is24Hour = true, zone = ZoneId.of("Europe/Rome")), format)
        assertFalse(dark)
        assertEquals("ui_window_bg is the light bg token", LightColors.bg.value.toLong(), windowBg)
        assertEquals(FIXED_ZONE, FIXED_CLOCK.zone)
        assertEquals("2026-10-09T10:30+02:00[Europe/Rome]", FIXED_NOW.toString())
    }

    @Test
    @Config(qualifiers = "+night")
    fun darkThemeMatchesTheNightResources() {
        var dark = false
        var windowBg = 0L
        show(dark = true) {
            dark = CassiniTheme.colors.isDark
            windowBg = colorResource(R.color.ui_window_bg).value.toLong()
        }
        assertTrue(dark)
        assertEquals("values-night ui_window_bg is the dark bg token", DarkColors.bg.value.toLong(), windowBg)
    }

    @Test fun darkWithoutTheNightQualifierFails() {
        val error = runCatching { show(dark = true) {} }.exceptionOrNull()
        assertTrue("expected a configuration mismatch, got $error", error is AssertionError)
    }

    /** [cutTexts] finds each way a text can fail to fit, and nothing in a text that fits. */
    @Test fun cutTextsFindsTextsThatDoNotFit() {
        show {
            Column(Modifier.width(80.dp)) {
                Text("Fits")
                Text("Shortened with an ellipsis", maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Cut at the edge of its width", softWrap = false, overflow = TextOverflow.Clip)
                Text("Wraps to more lines than it may have", maxLines = 2, overflow = TextOverflow.Clip)
            }
        }
        assertEquals(
            listOf(
                "Shortened with an ellipsis (ellipsized)",
                "Cut at the edge of its width (cut at the bottom)",
                "Wraps to more lines than it may have (more lines than maxLines)",
            ),
            cutTexts(),
        )
    }
}

/** A subclass the way LocaleScreenshotTest will be: `+it` and the Italian override. */
@Config(qualifiers = "+it")
class BoardTestItalianSetupTest : BoardTest() {
    override val locale: Locale = Locale.ITALIAN

    @Test fun formatFollowsTheOverride() {
        var format: FormatSettings? = null
        show { format = LocalFormat.current }
        assertEquals(Locale.ITALIAN, format?.locale)
    }
}

/** A subclass the way FontScaleScreenshotTest will be: `@Config(fontScale)` and the override. */
@Config(fontScale = 1.5f)
class BoardTestFontScaleSetupTest : BoardTest() {
    override val fontScale: Float = 1.5f

    @Test fun composeSeesTheFontScale() {
        var scale = 0f
        show { scale = LocalDensity.current.fontScale }
        assertEquals(1.5f, scale, 0.001f)
    }
}
