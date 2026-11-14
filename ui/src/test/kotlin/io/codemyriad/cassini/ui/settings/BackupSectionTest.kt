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

    @Test fun defaultsShowThreeAccessibleSwitchesAllOn() {
        show(BackupSettingsUi(transfer = true, transferAvailable = true, quotaExceeded = false))
        rule.onNodeWithText("MOVING AND BACKUP").assertExists()
        rule.onNodeWithText("Encrypted cloud backup").assertIsOn().performClick()
        rule.onNodeWithText("Move to a new phone").assertIsOn().performClick()
        rule.onNodeWithText("Include saved voices in backups").assertIsOn().performClick()
        assertEquals(
            listOf(SettingsEvent.Cloud(false), SettingsEvent.Transfer(false), SettingsEvent.IncludeVoices(false)),
            events,
        )
        // A switch changes only when the stored choice does.
        rule.onNodeWithText("Encrypted cloud backup").assertIsOn()
        rule.onNodeWithText(BODY_ON, substring = true).assertExists()
        rule.onAllNodesWithText(PHONE_ONLY).assertCountEquals(0)
    }

    @Test fun cloudOnTransferOffSaysCloudRestoreStillBringsMeetingsBack() {
        show(BackupSettingsUi(transfer = false, transferAvailable = true, quotaExceeded = false))
        rule.onNodeWithText("Move to a new phone").assertIsOff()
        rule.onNodeWithText(
            "Nothing is copied directly to a new phone. Meetings in your cloud backup come back when you restore it on a new phone.",
        ).assertExists()
        rule.onAllNodesWithText(PHONE_ONLY).assertCountEquals(0)
    }

    @Test fun cloudOffTransferOnMakesNoPhoneOnlyClaim() {
        show(BackupSettingsUi(transfer = true, transferAvailable = true, quotaExceeded = false, cloud = false))
        rule.onNodeWithText("Encrypted cloud backup").assertIsOff()
        rule.onNodeWithText(CLOUD_OFF).assertExists()
        rule.onAllNodesWithText(PHONE_ONLY).assertCountEquals(0)
    }

    @Test fun savedVoicesOffSaysTheyStayOnThisPhone() {
        show(BackupSettingsUi(transfer = true, transferAvailable = true, quotaExceeded = false, voices = false))
        rule.onNodeWithText("Include saved voices in backups").assertIsOff()
        rule.onNodeWithText("Saved voices stay on this phone only", substring = true).assertExists()
    }

    @Test fun learnMoreOpensWhatIsBackedUpAndWhoCanReadIt() {
        show(BackupSettingsUi(transfer = true, transferAvailable = true, quotaExceeded = false))
        rule.onNodeWithText("Learn more").performClick()
        rule.onNodeWithText("What is backed up").assertExists()
        rule.onNodeWithText("Android cloud backup").assertExists()
        rule.onNodeWithText("A copy you keep yourself").assertExists()
        rule.onNodeWithText("Close").performClick()
        rule.onAllNodesWithText("What is backed up").assertCountEquals(0)
    }

    @Test fun cloudQuotaFailureNamesTheAndroidLimit() {
        show(
            BackupSettingsUi(
                transfer = false,
                transferAvailable = true,
                quotaExceeded = false,
                cloudQuotaExceeded = true,
            ),
        )
        rule.onNodeWithText("Your meetings exceed Android’s 25 MB cloud backup limit", substring = true).assertExists()
        assertNoQuotaWarning()
    }

    @Test fun phoneOnlyStateExplainsTheConsequenceAndOffersExport() {
        show(BackupSettingsUi(transfer = false, transferAvailable = true, quotaExceeded = true, cloud = false))
        rule.onNodeWithText("Move to a new phone").assertIsOff()
        assertNoQuotaWarning()
        rule.onNodeWithText(
            "Nothing is copied to a new phone. If this phone is lost or replaced, your meetings are gone unless you exported them.",
        ).assertExists()
        rule.onNodeWithText(CLOUD_OFF).assertExists()
        rule.onNodeWithText(PHONE_ONLY).assertExists()
        rule.onNodeWithText("To keep a copy somewhere safe, use Export All as ZIP.").assertExists()
    }

    @Test fun beforeAndroid9NoChannelCanBeToggledAndEachExplainsWhy() {
        show(
            BackupSettingsUi(
                transfer = true,
                transferAvailable = false,
                quotaExceeded = true,
                cloudQuotaExceeded = true,
            ),
        )
        rule.onNodeWithText("Encrypted cloud backup").assertIsNotEnabled().assertIsOff()
        rule.onNodeWithText("Move to a new phone").assertIsNotEnabled().assertIsOff()
        rule.onNodeWithText("Include saved voices in backups").assertIsNotEnabled().assertIsOff()
        assertNoQuotaWarning()
        rule.onAllNodesWithText("Choosing this needs Android 9 or newer.").assertCountEquals(3)
        rule.onNodeWithText(PHONE_ONLY).assertExists()
    }

    private fun assertNoQuotaWarning() {
        rule.onAllNodesWithText(
            "Your meetings and saved voices are too large to move in one transfer. Export your meetings as a ZIP instead.",
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
        rule.onNodeWithText("Encrypted cloud backup").assertIsNotEnabled()
        rule.onNodeWithText("Include saved voices in backups").assertIsNotEnabled()
        rule.onNodeWithText("Couldn’t save this choice. Try again.").assertExists()
        rule.onNodeWithText(
            "Your meetings and saved voices are too large to move in one transfer. " +
                "Export your meetings as a ZIP instead.",
        ).assertExists()
    }

    private companion object {
        const val BODY_ON = "Meetings are included in your phone’s backups"
        const val CLOUD_OFF = "Meetings are not added to cloud backups. Turning this off stops future backups; " +
            "it does not delete backups already stored in your cloud account."
        const val PHONE_ONLY = "Meetings and saved voices stay on this phone only. They are not backed up or copied " +
            "to a new phone; to keep a copy, use Export All as ZIP."
    }
}
