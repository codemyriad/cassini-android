package io.codemyriad.cassini.ui.library

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.codemyriad.cassini.ui.theme.CassiniTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The once-only backup card (PARITY Wave 6 contract §3, D98): Android wording and its three ways out. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w390dp-h844dp-xhdpi")
class BackupCardTest {
    @get:Rule val rule = createComposeRule()
    private val events = mutableListOf<LibraryEvent>()

    @Test fun theCardOffersPhoneOnlyKeepBackupOnAndANamedCloseButton() {
        rule.setContent { CassiniTheme { BackupCard(events::add) } }
        rule.onNodeWithText("Meetings in your backups").assertExists()
        rule.onNodeWithText(
            "If this phone backs up, your meetings and saved voices go into its encrypted backup",
            substring = true,
        ).assertExists()
        rule.onNodeWithText("This phone only").performClick()
        rule.onNodeWithText("Keep in backup").performClick()
        rule.onNodeWithContentDescription("Dismiss backup notice").performClick()
        assertEquals(
            listOf(LibraryEvent.KeepOnPhoneOnly, LibraryEvent.DismissBackupCard, LibraryEvent.DismissBackupCard),
            events,
        )
    }

    @Test fun theLibraryShowsTheCardOnlyWhenAsked() {
        rule.setContent { CassiniTheme { LibraryScreen(LibraryUiState(backupCard = true), events::add) } }
        rule.onNodeWithText("Meetings in your backups").assertExists()
        val notice = rule.onNodeWithTag("backup-notice").fetchSemanticsNode().boundsInRoot
        val record = rule.onNodeWithText("Record", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("notice stays above the bottom actions", notice.bottom <= record.top)
    }
}
