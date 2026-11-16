package io.codemyriad.cassini.ui.settings

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.codemyriad.cassini.ui.R
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
        rule.runOnIdle {
            assertEquals(
                listOf(
                    SettingsEvent.SpeakerChoice(ModelPartUi.SPEAKERS, false),
                    SettingsEvent.SpeakerChoice(ModelPartUi.VOICES, true),
                ),
                events,
            )
            back.onBackPressed()
        }
        rule.onNodeWithText("Language").assertExists()
    }

    @Test fun aboutHasFourDistinctDestinations() {
        val events = mutableListOf<SettingsEvent>()
        rule.setContent {
            CassiniTheme {
                SettingsScreen(BoardFixtures.settings, events::add, initialPage = SettingsPage.ABOUT)
            }
        }
        for (label in listOf("Help", "Privacy", "Licences and notices", "Source code")) {
            rule.onNodeWithText(label).performScrollTo().performClick()
        }
        rule.runOnIdle {
            assertEquals(
                listOf(
                    SettingsEvent.OpenHelp,
                    SettingsEvent.OpenPrivacy,
                    SettingsEvent.OpenLicenses,
                    SettingsEvent.OpenSourceCode,
                ),
                events,
            )
        }
    }

    @Test fun privacyIsQualifiedForEveryTransportAndUnknownState() {
        val base = BackupSettingsUi(transfer = true, transferAvailable = true, quotaExceeded = false)
        assertEquals(R.string.ui_privacy_processing, settingsPrivacy(null))
        assertEquals(R.string.ui_privacy_with_backup, settingsPrivacy(base))
        assertEquals(R.string.ui_privacy_processing, settingsPrivacy(base.copy(cloud = false)))
        assertEquals(R.string.ui_privacy_phone_only, settingsPrivacy(base.copy(cloud = false, transfer = false)))
        assertEquals(
            R.string.ui_privacy_phone_only,
            settingsPrivacy(base.copy(cloudAvailable = false, transferAvailable = false)),
        )
    }
}
