package org.cassini.ui.screens

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import org.cassini.ui.fixtures.BoardFixtures
import org.cassini.ui.fixtures.VariantFixtures
import org.cassini.ui.format.FIXED_ZONE
import org.cassini.ui.format.ModelPartUi
import org.cassini.ui.format.ProvideFixedFormat
import org.cassini.ui.licenses.LicenseEntry
import org.cassini.ui.licenses.LicensesBackTo
import org.cassini.ui.licenses.LicensesEvent
import org.cassini.ui.licenses.LicensesScreen
import org.cassini.ui.models.ModelDownloadEvent
import org.cassini.ui.models.ModelDownloadScreen
import org.cassini.ui.settings.LanguageChoice
import org.cassini.ui.settings.SettingsEvent
import org.cassini.ui.settings.SettingsScreen
import org.cassini.ui.theme.CassiniTheme
import org.cassini.ui.trash.TrashBackTo
import org.cassini.ui.trash.TrashEvent
import org.cassini.ui.trash.TrashScreen
import org.cassini.ui.voices.SavedVoicesEvent
import org.cassini.ui.voices.SavedVoicesScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/**
 * What Model download, Settings, Saved voices, Trash and Licenses send (features.md sections 5, 8, 10 and 11): each
 * control's event, the rows that are buttons and the ones that are not, the person menu, and back in the licence text.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class SettingsBehaviourTest {
    @get:Rule val rule = createComposeRule()

    private lateinit var back: OnBackPressedDispatcher

    private fun setThemed(content: @Composable () -> Unit) {
        rule.setContent {
            back = requireNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            CassiniTheme(darkTheme = false) {
                ProvideFixedFormat(locale = Locale.ENGLISH, is24Hour = true, zone = FIXED_ZONE, content = content)
            }
        }
    }

    @Test fun modelDownloadControlsSendTheirEvents() {
        val events = mutableListOf<ModelDownloadEvent>()
        setThemed { ModelDownloadScreen(BoardFixtures.modelDownload, onEvent = { events += it }) }
        rule.onNodeWithContentDescription("Close").performClick()
        rule.onNodeWithText("Where the models come from · Privacy").performClick()
        rule.onNodeWithText("Download and transcribe").performClick()
        rule.onNodeWithText("Not now — keep the audio").performClick()
        // The figure keeps its unit on its line with a no-break space.
        rule.onNodeWithText("730\u00A0MB", substring = true).assertExists()
        rule.runOnIdle {
            assertEquals(
                listOf(
                    ModelDownloadEvent.Close,
                    ModelDownloadEvent.OpenSources,
                    ModelDownloadEvent.Download,
                    ModelDownloadEvent.NotNow,
                ),
                events,
            )
        }
    }

    /** features.md section 0: the body total is the sum of the rounded rows (640 + 62), not the rounded byte sum (700). */
    @Test fun modelDownloadTotalIsTheSumOfTheRoundedRows() {
        val state = VariantFixtures.modelDownload.getValue("models-speech-speakers")
        setThemed { ModelDownloadScreen(state, onEvent = {}) }
        rule.onNodeWithText("640 MB").assertExists()
        rule.onNodeWithText("62 MB").assertExists()
        rule.onNodeWithText("about 702\u00A0MB", substring = true).assertExists()
    }

    @Test fun modelDownloadWithoutANoteOnlyDownloads() {
        setThemed { ModelDownloadScreen(VariantFixtures.modelDownload.getValue("models-without-note"), onEvent = {}) }
        rule.onNodeWithText("Download").assertExists()
        rule.onAllNodesWithText("Download and transcribe").assertCountEquals(0)
    }

    @Test fun settingsLanguageRowsAndLinkSendTheirEvents() {
        val events = mutableListOf<SettingsEvent>()
        val state = VariantFixtures.settings.getValue("settings-model-statuses").copy(notificationsOff = true)
        setThemed { SettingsScreen(state, onEvent = { events += it }) }
        rule.onNodeWithText("Phone").assertIsSelected()
        rule.onNodeWithText("Italiano").performClick()
        rule.onNodeWithText("Turn on").performClick()
        // A Not downloaded row opens Model download; the other model rows are not buttons.
        rule.onNode(hasText("Recognising saved voices", substring = true) and hasClickAction()).performClick()
        rule.onNode(hasText("Speech recognition", substring = true)).assertHasNoClickAction()
        rule.onNodeWithText("Saved voices").performScrollTo().performClick()
        rule.onNodeWithText("Trash").performScrollTo().performClick()
        rule.onNodeWithText("Free software under GPLv3", substring = true).performScrollTo().performFirstLinkClick()
        rule.onNodeWithContentDescription("Back to library").performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(
                listOf(
                    SettingsEvent.Language(LanguageChoice.ITALIAN),
                    SettingsEvent.TurnOnNotifications,
                    SettingsEvent.OpenModel(ModelPartUi.VOICES),
                    SettingsEvent.OpenVoices,
                    SettingsEvent.OpenTrash,
                    SettingsEvent.OpenLicenses,
                    SettingsEvent.Back,
                ),
                events,
            )
        }
    }

    @Test fun aModelRowReadsItsStatusWithItsName() {
        setThemed { SettingsScreen(BoardFixtures.settings, onEvent = {}) }
        rule.onNode(hasText("Speech recognition") and hasText("Ready") and hasText("INT8 · 640 MB")).assertExists()
    }

    @Test fun theOptionsMenuRenamesAndForgets() {
        val events = mutableListOf<SavedVoicesEvent>()
        setThemed {
            SavedVoicesScreen(VariantFixtures.savedVoices.getValue("voices-menu"), onEvent = { events += it })
        }
        rule.onNodeWithText("Rename…").performClick()
        rule.onNodeWithText("Forget…").performClick()
        rule.onNodeWithContentDescription("Options for Giulia").performClick()
        rule.onNodeWithText("Forget everyone…").performClick()
        rule.onNodeWithContentDescription("Back to settings").performClick()
        rule.runOnIdle {
            assertEquals(
                listOf(
                    SavedVoicesEvent.Rename("anna"),
                    SavedVoicesEvent.Forget("anna"),
                    SavedVoicesEvent.OpenMenu("giulia"),
                    SavedVoicesEvent.ForgetEveryone,
                    SavedVoicesEvent.Back,
                ),
                events,
            )
        }
    }

    @Test fun readOnlyVoicesCannotBeEdited() {
        setThemed { SavedVoicesScreen(VariantFixtures.savedVoices.getValue("voices-error"), onEvent = {}) }
        rule.onNodeWithText("Saved voices can’t be read").assertExists()
        rule.onNodeWithContentDescription("Options for Giulia").assertIsNotEnabled()
        rule.onNodeWithText("Forget everyone…").assertIsNotEnabled()
    }

    @Test fun noSavedVoicesHidesForgetEveryone() {
        setThemed { SavedVoicesScreen(VariantFixtures.savedVoices.getValue("voices-empty"), onEvent = {}) }
        rule.onNodeWithText("No saved voices yet").assertExists()
        rule.onAllNodesWithText("Forget everyone…").assertCountEquals(0)
    }

    @Test fun trashCardsAndHeaderSendTheirEvents() {
        val events = mutableListOf<TrashEvent>()
        setThemed { TrashScreen(BoardFixtures.trash, onEvent = { events += it }) }
        rule.onAllNodesWithText("Restore")[1].performClick()
        rule.onAllNodesWithText("Delete now")[0].performClick()
        rule.onNodeWithText("Empty").performClick()
        rule.onNodeWithText("Undo").performClick()
        rule.onNodeWithContentDescription("Back to library").performClick()
        // The duration is read in words.
        rule.onNodeWithContentDescription("18 seconds", substring = true).assertExists()
        rule.runOnIdle {
            assertEquals(
                listOf(
                    TrashEvent.Restore("recovered"),
                    TrashEvent.DeleteNow("test-recording"),
                    TrashEvent.Empty,
                    TrashEvent.Undo,
                    TrashEvent.Back,
                ),
                events,
            )
        }
    }

    @Test fun anEmptyTrashHasNoEmptyButton() {
        setThemed {
            TrashScreen(VariantFixtures.trash.getValue("trash-empty").copy(backTo = TrashBackTo.SETTINGS), {})
        }
        rule.onNodeWithText("Trash is empty").assertExists()
        rule.onAllNodesWithText("Empty").assertCountEquals(0)
        rule.onNodeWithContentDescription("Back to settings").assertExists()
    }

    @Test fun licenceRowsOpenAndBackClosesTheText() {
        val events = mutableListOf<LicensesEvent>()
        setThemed { LicensesScreen(VariantFixtures.licenses.getValue("licenses"), onEvent = { events += it }) }
        rule.onNodeWithText("IBM Plex Mono").performClick()
        rule.onNodeWithText("Source code on GitHub").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Back to settings").performClick()
        rule.runOnIdle {
            assertEquals(
                listOf(
                    LicensesEvent.Open(LicenseEntry.PLEX_MONO),
                    LicensesEvent.Open(LicenseEntry.SOURCE_CODE),
                    LicensesEvent.Back,
                ),
                events,
            )
        }
    }

    @Test fun backInTheLicenceTextClosesIt() {
        val events = mutableListOf<LicensesEvent>()
        setThemed { LicensesScreen(VariantFixtures.licenses.getValue("licenses-text"), onEvent = { events += it }) }
        rule.onNodeWithText("Instrument Sans").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        rule.runOnIdle { back.onBackPressed() }
        rule.onNodeWithContentDescription("Back").performClick()
        rule.runOnIdle { assertEquals(listOf(LicensesEvent.CloseText, LicensesEvent.CloseText), events) }
    }

    /** Licenses opened from Model download does not name Settings as the back destination. */
    @Test fun licensesFromModelDownloadSaysBack() {
        val state = VariantFixtures.licenses.getValue("licenses").copy(backTo = LicensesBackTo.MODEL_DOWNLOAD)
        setThemed { LicensesScreen(state, onEvent = {}) }
        rule.onNodeWithContentDescription("Back").assertExists()
        rule.onNodeWithContentDescription("Back to settings").assertDoesNotExist()
    }
}
