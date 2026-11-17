package io.codemyriad.cassini.ui.settings

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
class BackupDetailsTest {
    @get:Rule val rule = createComposeRule()
    private val base = BackupSettingsUi(transfer = true, transferAvailable = true, quotaExceeded = false)

    @Test fun detailsMatchEveryChannelAndVoiceChoiceAndNeverCopySettingsOrModels() {
        val state = mutableStateOf(base)
        rule.setContent {
            CassiniTheme {
                SettingsScreen(
                    BoardFixtures.settings.copy(backup = state.value),
                    {},
                    initialPage = SettingsPage.BACKUP_DETAILS,
                )
            }
        }
        val cases = listOf(
            base,
            base.copy(voices = false),
            base.copy(transfer = false),
            base.copy(transfer = false, voices = false),
            base.copy(cloud = false),
            base.copy(cloud = false, voices = false),
            base.copy(cloud = false, transfer = false),
            base.copy(cloud = false, transfer = false, voices = false),
        )
        for (choice in cases) {
            val (transfer, cloud, voices) = Triple(choice.transfer, choice.cloud, choice.voices)
            rule.runOnIdle { state.value = choice }
            row("Settings", "Not copied")
            row("Speech models", "Not copied")
            row("Meetings", if (cloud || transfer) "Copied" else "Not copied")
            row("Saved voices", if ((cloud || transfer) && voices) "Copied" else "Not copied")
            visible("Android cloud backup", cloud)
            visible("The new phone", transfer)
            visible("Copied with your meetings", voices && (cloud || transfer))
            rule.onNodeWithText("A ZIP you export").assertExists()
        }
    }

    @Test fun ownCopyCallsTheExportOwnerAndDisablesWhileUnavailable() {
        var exports = 0
        val enabled = mutableStateOf(true)
        rule.setContent {
            CassiniTheme {
                SettingsScreen(
                    BoardFixtures.settings.copy(backup = base),
                    {},
                    initialPage = SettingsPage.BACKUP,
                    exportEnabled = enabled.value,
                    onExport = { exports++ },
                )
            }
        }
        rule.onNodeWithText("Export All as ZIP…").performScrollTo().performClick()
        assertEquals(1, exports)
        rule.runOnIdle { enabled.value = false }
        rule.onNodeWithText("Export All as ZIP…").assertIsNotEnabled()
    }

    private fun visible(text: String, shown: Boolean) {
        if (shown) {
            rule.onNodeWithText(text).assertExists()
        } else {
            rule.onNodeWithText(text).assertDoesNotExist()
        }
    }

    private fun row(title: String, status: String) = rule.onNode(hasText(title) and hasText(status)).assertExists()
}
