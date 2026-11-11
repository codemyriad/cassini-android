package io.codemyriad.cassini.ui.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w390dp-h844dp-xhdpi")
class BackupSectionTest {
    @get:Rule val rule = createComposeRule()
    private val events = mutableListOf<SettingsEvent>()

    private fun show(state: BackupSettingsUi) {
        rule.setContent {
            CassiniTheme {
                androidx.compose.foundation.layout.Column {
                    BackupSection(state, events::add)
                }
            }
        }
    }

    @Test fun transferHasAccessibleStateAndCloudHasNoUi() {
        show(BackupSettingsUi(transfer = true, transferAvailable = true, quotaExceeded = false))
        rule.onNodeWithText("Move to a new phone").assertIsOn().performClick()
        rule.onAllNodesWithText("Android cloud backup").assertCountEquals(0)
        assertEquals(listOf(SettingsEvent.Transfer(false)), events)
        rule.onNodeWithText("Move to a new phone").assertIsOn()
        rule.onAllNodesWithText("Android cloud backup").assertCountEquals(0)
    }

    @Test fun disabledTransferExplainsTheConsequenceAndOffersExport() {
        show(BackupSettingsUi(transfer = false, transferAvailable = true, quotaExceeded = true))
        rule.onNodeWithText("Move to a new phone").assertIsOff()
        assertNoQuotaWarning()
        rule.onNodeWithText(
            "Nothing is copied to a new phone. If this phone is lost or replaced, your notes are gone unless you exported them.",
        ).assertExists()
        rule.onNodeWithText("To keep a copy somewhere safe, use Export All as ZIP.").assertExists()
    }

    @Test fun unavailableTransferCannotBeToggledAndExplainsWhy() {
        show(BackupSettingsUi(transfer = true, transferAvailable = false, quotaExceeded = true))
        rule.onNodeWithText("Move to a new phone").assertIsNotEnabled().assertIsOff()
        assertNoQuotaWarning()
        rule.onNodeWithText("Choosing this needs Android 9 or newer.").assertExists()
    }

    private fun assertNoQuotaWarning() {
        rule.onAllNodesWithText(
            "Your notes and saved voices are too large to move in one transfer. Export your notes as a ZIP instead.",
        ).assertCountEquals(0)
    }

    @Test fun aWriteInProgressDisablesTransferAndActualQuotaFailureOffersZip() {
        show(
            BackupSettingsUi(
                transfer = true,
                transferAvailable = true,
                quotaExceeded = true,
                saving = true,
                error = true
            )
        )
        rule.onNodeWithText("Move to a new phone").assertIsNotEnabled()
        rule.onAllNodesWithText("Android cloud backup").assertCountEquals(0)
        rule.onNodeWithText("Couldn’t save this choice. Try again.").assertExists()
        rule.onNodeWithText(
            "Your notes and saved voices are too large to move in one transfer. " +
                "Export your notes as a ZIP instead.",
        ).assertExists()
    }
}
