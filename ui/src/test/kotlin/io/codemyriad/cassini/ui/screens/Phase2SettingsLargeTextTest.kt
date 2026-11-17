package io.codemyriad.cassini.ui.screens

import io.codemyriad.cassini.ui.BoardTest
import io.codemyriad.cassini.ui.fixtures.BoardFixtures
import io.codemyriad.cassini.ui.settings.SettingsPage
import io.codemyriad.cassini.ui.settings.SettingsScreen
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import java.util.Locale

/** The deeper pages must wrap long translations at accessibility font sizes. */
@Config(qualifiers = "+fr", fontScale = 1.5f)
class Phase2SettingsLargeTextTest : BoardTest() {
    override val locale: Locale = Locale.FRENCH
    override val fontScale: Float = 1.5f

    @Test fun transcriptionWraps() = wraps(SettingsPage.TRANSCRIPTION)

    @Test fun speakersWrap() = wraps(SettingsPage.SPEAKERS)

    @Test fun aboutWraps() = wraps(SettingsPage.ABOUT)

    @Test fun backupWraps() = wraps(SettingsPage.BACKUP)

    @Test fun backupDetailsWraps() = wraps(SettingsPage.BACKUP_DETAILS)

    private fun wraps(page: SettingsPage) {
        show { SettingsScreen(BoardFixtures.settings, {}, initialPage = page) }
        val cut = cutTexts()
        assertTrue("Text must wrap: $cut", cut.isEmpty())
    }
}
