package io.codemyriad.cassini.ui.screens

import io.codemyriad.cassini.ui.BoardTest
import io.codemyriad.cassini.ui.fixtures.BoardFixtures
import io.codemyriad.cassini.ui.format.ModelPartUi
import io.codemyriad.cassini.ui.settings.BackupSettingsUi
import io.codemyriad.cassini.ui.settings.ModelStatusUi
import io.codemyriad.cassini.ui.settings.SettingsPage
import io.codemyriad.cassini.ui.settings.SettingsScreen
import kotlinx.collections.immutable.toImmutableList
import org.junit.Test
import org.robolectric.annotation.Config

/** D103 light/dark evidence, with real choices and explicitly synthetic Library totals. */
class Phase2SettingsScreenshotTest : BoardTest() {

    @Test fun settingsLight() = settings(SettingsPage.MAP)

    @Test fun transcriptionLight() = settings(SettingsPage.TRANSCRIPTION)

    @Test fun speakersLight() = settings(SettingsPage.SPEAKERS)

    @Test fun aboutLight() = settings(SettingsPage.ABOUT)

    @Test
    @Config(qualifiers = "+night")
    fun settingsDark() = settings(SettingsPage.MAP, true)

    @Test
    @Config(qualifiers = "+night")
    fun transcriptionDark() = settings(SettingsPage.TRANSCRIPTION, true)

    @Test
    @Config(qualifiers = "+night")
    fun speakersDark() = settings(SettingsPage.SPEAKERS, true)

    @Test
    @Config(qualifiers = "+night")
    fun aboutDark() = settings(SettingsPage.ABOUT, true)

    private fun settings(page: SettingsPage, dark: Boolean = false) {
        val state = BoardFixtures.settings.copy(
            models = BoardFixtures.settings.models.map { row ->
                if (page == SettingsPage.TRANSCRIPTION && row.part == ModelPartUi.SPEECH) {
                    row.copy(status = ModelStatusUi.NotDownloaded)
                } else {
                    row
                }
            }.toImmutableList(),
            version = "0.4.2-beta.3",
            meetings = 38,
            audioHours = 41,
            backup = BackupSettingsUi(transfer = true, transferAvailable = true, quotaExceeded = false),
        )
        board(if (dark) "dark" else "light", "phase2-settings-${page.name.lowercase()}", dark) {
            SettingsScreen(state, {}, initialPage = page)
        }
    }
}
