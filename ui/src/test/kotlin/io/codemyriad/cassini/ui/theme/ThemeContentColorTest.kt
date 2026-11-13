package io.codemyriad.cassini.ui.theme

import android.content.Context
import android.content.res.Configuration
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.codemyriad.cassini.ui.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The theme's content colour is ink (docs/design/ui.md section 4.2): default ripples and Texts without a colour
 * follow it. Material 3 alone leaves it at black, which does not show on the dark theme.
 */
@RunWith(RobolectricTestRunner::class)
class ThemeContentColorTest {
    @get:Rule val rule = createComposeRule()

    private fun contentColor(dark: Boolean): Color {
        var color = Color.Unspecified
        rule.setContent { CassiniTheme(darkTheme = dark) { color = LocalContentColor.current } }
        rule.waitForIdle()
        return color
    }

    @Test fun lightContentIsLightInk() = assertEquals(LightColors.ink, contentColor(dark = false))

    @Test fun darkContentIsDarkInk() = assertEquals(DarkColors.ink, contentColor(dark = true))

    @Test fun windowColorsFollowTheActiveTokenPaletteInBothSchemes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val modes = listOf(Configuration.UI_MODE_NIGHT_NO to LightColors, Configuration.UI_MODE_NIGHT_YES to DarkColors)
        for ((night, colors) in modes) {
            val config = Configuration(context.resources.configuration).apply { uiMode = night }
            val resources = context.createConfigurationContext(config).resources
            assertEquals(colors.bg.toArgb(), resources.getColor(R.color.ui_window_bg, context.theme))
            assertEquals(colors.ink.toArgb(), resources.getColor(R.color.ui_logo_ink, context.theme))
        }
    }
}
