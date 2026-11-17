package io.codemyriad.cassini.ui.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.codemyriad.cassini.ui.fixtures.BoardFixtures
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
                SettingsScreen(
                    BoardFixtures.settings.copy(backup = state),
                    events::add,
                    initialPage = SettingsPage.BACKUP,
                    exportEnabled = true,
                )
            }
        }
    }

    @Test fun defaultsShowThreeAccessibleSwitchesAllOn() {
        show(BackupSettingsUi(transfer = true, transferAvailable = true, quotaExceeded = false))
        rule.onNodeWithText("Your meetings move with you to a new phone").assertExists()
        rule.onNodeWithText("Encrypted cloud backup").performScrollTo().assertIsOn().performClick()
        rule.onNodeWithText("Move to a new phone").performScrollTo().assertIsOn().performClick()
        rule.onNodeWithText("Back up saved voices").performScrollTo().assertIsOn().performClick()
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
        rule.onNodeWithText("Back up saved voices").assertIsOff()
        rule.onNodeWithText("Saved voices stay on this phone only", substring = true).assertExists()
    }

    @Test fun detailsOpensWhatMovesAndReturnsToBackup() {
        show(BackupSettingsUi(transfer = true, transferAvailable = true, quotaExceeded = false))
        rule.onNodeWithText("What moves").performScrollTo().performClick()
        rule.onNodeWithText("WHAT MOVES").assertExists()
        rule.onNodeWithText("Android cloud backup").assertExists()
        rule.onNodeWithText("A ZIP you export").assertExists()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithText("Your meetings move with you to a new phone").assertExists()
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
        rule.onNodeWithText("Save every meeting to a folder, a drive or another app.").assertExists()
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
        rule.onNodeWithText("Back up saved voices").assertIsNotEnabled().assertIsOff()
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
        rule.onNodeWithText("Back up saved voices").assertIsNotEnabled()
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
