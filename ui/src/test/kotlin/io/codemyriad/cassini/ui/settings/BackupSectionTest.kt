package io.codemyriad.cassini.ui.settings

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
        rule.setContent { CassiniTheme { androidx.compose.foundation.layout.Column { BackupSection(state, events::add) } } }
    }

    @Test fun switchesHaveIndependentAccessibleStateAndDoNotInventSuccessfulPersistence() {
        show(BackupSettingsUi(transfer = true, cloud = false, transferAvailable = true, quotaExceeded = false))
        rule.onNodeWithText("Transfer to a new phone").assertIsOn().performClick()
        rule.onNodeWithText("Android cloud backup").assertIsOff().performClick()
        assertEquals(listOf(SettingsEvent.Transfer(false), SettingsEvent.CloudBackup(true)), events)
        rule.onNodeWithText("Transfer to a new phone").assertIsOn()
        rule.onNodeWithText("Android cloud backup").assertIsOff()
    }

    @Test fun unavailableTransferCannotBeToggledAndExplainsWhy() {
        show(BackupSettingsUi(transfer = true, cloud = false, transferAvailable = false, quotaExceeded = false))
        rule.onNodeWithText("Transfer to a new phone").assertIsNotEnabled().assertIsOff()
        rule.onNodeWithText("Separate phone-transfer controls need Android 9 or newer.").assertExists()
    }

    @Test fun aWriteInProgressDisablesBothSwitchesAndActualQuotaFailureIsVisible() {
        show(BackupSettingsUi(transfer = true, cloud = true, transferAvailable = true, quotaExceeded = true, saving = true, error = true))
        rule.onNodeWithText("Transfer to a new phone").assertIsNotEnabled()
        rule.onNodeWithText("Android cloud backup").assertIsNotEnabled()
        rule.onNodeWithText("Couldn’t save this choice. Try again.").assertExists()
        rule.onNodeWithText(
            "Android could not back up the whole library because it exceeds the backup limit. Save copies of your important notes.",
        ).assertExists()
    }
}
