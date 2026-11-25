package io.codemyriad.cassini.ui.settings

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.codemyriad.cassini.ui.fixtures.BoardFixtures
import io.codemyriad.cassini.ui.format.ModelPartUi
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
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class SettingsMapTest {
    @get:Rule val rule = createComposeRule()

    @Test fun speakerSwitchesSendIndependentChoicesAndBackReturnsToMap() {
        val events = mutableListOf<SettingsEvent>()
        lateinit var back: OnBackPressedDispatcher
        rule.setContent {
            back = requireNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            CassiniTheme {
                SettingsScreen(BoardFixtures.settings.copy(recogniseVoices = false), events::add)
            }
        }
        rule.onNodeWithText("Speakers").performClick()
        rule.onNodeWithText("Tell speakers apart").assertIsOn().performClick()
        rule.onNodeWithText("Recognise saved voices").assertIsOff().performClick()
        rule.onNodeWithText("Name people automatically").assertIsOff().performClick()
        rule.runOnIdle {
            assertEquals(
                listOf(
                    SettingsEvent.SpeakerChoice(ModelPartUi.SPEAKERS, false),
                    SettingsEvent.SpeakerChoice(ModelPartUi.VOICES, true),
                    SettingsEvent.AutoNameVoices(true),
                ),
                events,
            )
            back.onBackPressed()
        }
        rule.onNodeWithText("Language").assertExists()
    }

    @Test fun libraryTotalsUseMinutesBelowAnHourAndWholeHoursAboveIt() {
        val duration = mutableStateOf(40L * 60_000)
        rule.setContent {
            CassiniTheme {
                SettingsScreen(BoardFixtures.settings.copy(meetings = 3, audioDurationMs = duration.value), {})
            }
        }
        rule.onNodeWithText("3 meetings, 40\u00a0min of audio.").assertExists()
        rule.runOnIdle { duration.value = 3_600_000 + 40L * 60_000 }
        rule.onNodeWithText("3 meetings, 1 hour of audio.").assertExists()
    }

    @Test fun aboutHasFiveDistinctDestinations() {
        val events = mutableListOf<SettingsEvent>()
        rule.setContent {
            CassiniTheme {
                SettingsScreen(BoardFixtures.settings, events::add, initialPage = SettingsPage.ABOUT)
            }
        }
        for (label in listOf("Help", "Report a problem", "Privacy", "Licences and notices", "Source code")) {
            rule.onNodeWithText(label).performScrollTo().performClick()
        }
        rule.runOnIdle {
            assertEquals(
                listOf(
                    SettingsEvent.OpenHelp,
                    SettingsEvent.OpenReportProblem,
                    SettingsEvent.OpenPrivacy,
                    SettingsEvent.OpenLicenses,
                    SettingsEvent.OpenSourceCode,
                ),
                events,
            )
        }
    }

    @Test fun topPageShowsFirstRunPrivacyLineAndOnOrOffBackupAnswer() {
        val base = BackupSettingsUi(transfer = true, transferAvailable = true, quotaExceeded = false)
        val backup = mutableStateOf(base)
        rule.setContent {
            CassiniTheme { SettingsScreen(BoardFixtures.settings.copy(backup = backup.value), {}) }
        }
        // QA 25 Nov: the Transcripts line says only where transcription runs; the backup line is on the Backup row.
        rule.onNodeWithText("Transcribed on this phone.").assertExists()
        rule.onNodeWithText("Transcribed on this phone. Backups follow your phone’s settings.").assertDoesNotExist()
        val backupRow = hasText("Backup") and hasText("Backups follow your phone’s settings.")
        rule.onNode(backupRow and hasText("On")).assertExists()
        rule.runOnIdle { backup.value = base.copy(cloud = false, transfer = false) }
        rule.onNode(backupRow and hasText("Off")).assertExists()
    }

    /** QA 25 Nov: "Export all meetings" moved from the Library to Your meetings; it greys out with nothing to export. */
    @Test fun yourMeetingsOffersExportAllMeetings() {
        var exports = 0
        val enabled = mutableStateOf(true)
        rule.setContent {
            CassiniTheme {
                SettingsScreen(BoardFixtures.settings, {}, exportEnabled = enabled.value, onExport = { exports++ })
            }
        }
        rule.onNodeWithText("Export all meetings").performScrollTo().assertIsEnabled().performClick()
        rule.runOnIdle {
            assertEquals(1, exports)
            enabled.value = false
        }
        rule.onNodeWithText("Export all meetings").assertIsNotEnabled()
    }
}
