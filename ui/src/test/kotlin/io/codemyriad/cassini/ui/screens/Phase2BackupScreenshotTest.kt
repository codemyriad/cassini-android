package io.codemyriad.cassini.ui.screens

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import io.codemyriad.cassini.ui.BoardTest
import io.codemyriad.cassini.ui.fixtures.BoardFixtures
import io.codemyriad.cassini.ui.library.LibraryScreen
import io.codemyriad.cassini.ui.settings.SettingsPage
import io.codemyriad.cassini.ui.settings.SettingsScreen
import org.junit.Test
import org.robolectric.annotation.Config

/** D103/D98 backup policy pages and the once-only notice, using synthetic meetings. */
class Phase2BackupScreenshotTest : BoardTest() {
    @Test fun backupLight() = backup(false, false)

    @Test fun backupEndLight() = backup(false, true)

    @Test fun detailsLight() = details(false, false)

    @Test fun detailsEndLight() = details(false, true)

    @Test fun noticeLight() = notice(false)

    @Test
    @Config(qualifiers = "+night")
    fun backupDark() = backup(true, false)

    @Test
    @Config(qualifiers = "+night")
    fun backupEndDark() = backup(true, true)

    @Test
    @Config(qualifiers = "+night")
    fun detailsDark() = details(true, false)

    @Test
    @Config(qualifiers = "+night")
    fun detailsEndDark() = details(true, true)

    @Test
    @Config(qualifiers = "+night")
    fun noticeDark() = notice(true)

    private fun backup(dark: Boolean, end: Boolean) {
        show(dark) {
            SettingsScreen(BoardFixtures.settings, {}, initialPage = SettingsPage.BACKUP, exportEnabled = true)
        }
        if (end) scroll("Save every meeting to a folder or app.")
        capture(if (dark) "dark" else "light", "phase2-backup" + if (end) "-end" else "")
    }

    private fun details(dark: Boolean, end: Boolean) {
        show(dark) { SettingsScreen(BoardFixtures.settings, {}, initialPage = SettingsPage.BACKUP_DETAILS) }
        if (end) scroll("Cassini itself never uploads or reads your meetings.")
        capture(if (dark) "dark" else "light", "phase2-backup-details" + if (end) "-end" else "")
    }

    private fun notice(dark: Boolean) = board(if (dark) "dark" else "light", "phase2-backup-notice", dark) {
        LibraryScreen(BoardFixtures.library.copy(progress = null, backupCard = true), {})
    }

    private fun scroll(text: String) {
        rule.mainClock.autoAdvance = true
        rule.onNodeWithText(text).performScrollTo()
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(1_000)
    }
}
