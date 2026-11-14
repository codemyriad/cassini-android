package io.codemyriad.cassini.ui.library

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.codemyriad.cassini.ui.theme.CassiniTheme
import org.junit.Assert.assertEquals
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
        rule.onNodeWithText("Keep them on this phone only").assertExists()
        rule.onNodeWithText("Your meetings and saved voices are included in encrypted cloud backup", substring = true)
            .assertExists()
        rule.onNodeWithText("Keep on this phone only").performClick()
        rule.onNodeWithText("Keep backup on").performClick()
        rule.onNodeWithContentDescription("Dismiss backup notice").performClick()
        assertEquals(
            listOf(LibraryEvent.KeepOnPhoneOnly, LibraryEvent.DismissBackupCard, LibraryEvent.DismissBackupCard),
            events,
        )
    }

    @Test fun theLibraryShowsTheCardOnlyWhenAsked() {
        rule.setContent { CassiniTheme { LibraryScreen(LibraryUiState(backupCard = true), events::add) } }
        rule.onNodeWithText("Keep them on this phone only").assertExists()
    }
}
