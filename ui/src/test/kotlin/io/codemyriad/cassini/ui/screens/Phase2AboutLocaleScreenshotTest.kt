package io.codemyriad.cassini.ui.screens

import androidx.compose.ui.test.onNodeWithText
import io.codemyriad.cassini.ui.BoardTest
import io.codemyriad.cassini.ui.settings.SettingsPage
import io.codemyriad.cassini.ui.settings.SettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Settings › About in Italian, light and dark (D-1090): the catalog's copyright line names the legal entity
 * "Codemyriad OÜ" (D81.1) in every language, not only in English.
 */
@Config(qualifiers = "+it")
class Phase2AboutLocaleScreenshotTest : BoardTest() {
    override val locale: Locale = Locale.ITALIAN

    @Test fun aboutLight() = about(dark = false)

    @Test
    @Config(qualifiers = "+night")
    fun aboutDark() = about(dark = true)

    private fun about(dark: Boolean) {
        board(if (dark) "it-dark" else "it", "phase2-settings-about", dark) {
            SettingsScreen(phase2SettingsState(SettingsPage.ABOUT), {}, initialPage = SettingsPage.ABOUT)
        }
        rule.onNodeWithText("Software libero con licenza GPLv3. © 2026 Codemyriad OÜ e collaboratori.").assertExists()
        assertEquals("Texts that do not fit", emptyList<String>(), cutTexts())
    }
}
