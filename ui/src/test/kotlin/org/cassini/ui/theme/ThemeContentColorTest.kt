package org.cassini.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
}
