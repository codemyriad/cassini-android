package io.codemyriad.cassini.ui.screens

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import io.codemyriad.cassini.ui.fixtures.BoardFixtures
import io.codemyriad.cassini.ui.fixtures.VariantFixtures
import io.codemyriad.cassini.ui.format.FIXED_ZONE
import io.codemyriad.cassini.ui.format.ModelPartUi
import io.codemyriad.cassini.ui.format.ProvideFixedFormat
import io.codemyriad.cassini.ui.licenses.LicenseEntry
import io.codemyriad.cassini.ui.licenses.LicensesBackTo
import io.codemyriad.cassini.ui.licenses.LicensesEvent
import io.codemyriad.cassini.ui.licenses.LicensesScreen
import io.codemyriad.cassini.ui.models.ModelDownloadEvent
import io.codemyriad.cassini.ui.models.ModelDownloadScreen
import io.codemyriad.cassini.ui.settings.LanguageChoice
import io.codemyriad.cassini.ui.settings.ModelStatusUi
import io.codemyriad.cassini.ui.settings.SettingsEvent
import io.codemyriad.cassini.ui.settings.SettingsScreen
import io.codemyriad.cassini.ui.settings.SpeechModelUi
import io.codemyriad.cassini.ui.theme.CassiniTheme
import io.codemyriad.cassini.ui.trash.TrashBackTo
import io.codemyriad.cassini.ui.trash.TrashEvent
import io.codemyriad.cassini.ui.trash.TrashScreen
import io.codemyriad.cassini.ui.voices.SavedVoicesEvent
import io.codemyriad.cassini.ui.voices.SavedVoicesScreen
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
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

    @Test fun singlePartStandaloneConsentUsesNeutralCopy() {
        val sample = VariantFixtures.modelDownload.getValue("models-without-note")
        val state = sample.copy(parts = persistentListOf(sample.parts.last()))
        setThemed { ModelDownloadScreen(state, onEvent = {}) }
        rule.onNodeWithText("Optional download", substring = true).assertExists()
        rule.onAllNodesWithText("recognising words and telling voices apart", substring = true).assertCountEquals(0)
    }

    @Test fun pendingOptionalRowsHaveAnActionButAnActiveParakeetDoesNot() {
        val events = mutableListOf<SettingsEvent>()
        val state = BoardFixtures.settings.copy(
            models = BoardFixtures.settings.models.map { row ->
                val status = if (row.part == ModelPartUi.SPEECH) ModelStatusUi.Downloading(10) else ModelStatusUi.Pending
                row.copy(status = status)
            }.toImmutableList(),
            speechChecking = false,
        )
        setThemed { SettingsScreen(state, onEvent = { events += it }) }
        rule.onNodeWithText("Parakeet Ultra").assertHasNoClickAction()
        rule.onNodeWithText("Telling speakers apart").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(listOf(SettingsEvent.OpenModel(ModelPartUi.SPEAKERS)), events) }
    }

    @Test fun unavailableWhistleHasNoActionAndParakeetCanBeSelected() {
        val events = mutableListOf<SettingsEvent>()
        val state = VariantFixtures.settings.getValue("settings-included-unavailable")
        setThemed { SettingsScreen(state, onEvent = { events += it }) }
        rule.onNode(hasText("Whistle") and hasText("Unavailable")).assertHasNoClickAction()
        rule.onNodeWithText("Parakeet Ultra").performClick()
        rule.runOnIdle {
            assertEquals(
                listOf(SettingsEvent.ChooseSpeech(io.codemyriad.cassini.ui.settings.SpeechModelUi.PARAKEET)),
                events,
            )
        }
    }

    @Test fun settingsLanguageRowsAndLinkSendTheirEvents() {
        val events = mutableListOf<SettingsEvent>()
        val state = VariantFixtures.settings.getValue("settings-model-statuses").copy(notificationsOff = true)
        setThemed { SettingsScreen(state, onEvent = { events += it }) }
        // The App language row names the choice and opens the sheet; a pick closes it.
        rule.onNode(hasText("Phone") and hasClickAction()).performClick()
        rule.onNode(hasText("Phone") and isSelectable()).assertIsSelected()
        rule.onNodeWithText("Italiano").performClick()
        rule.onAllNodes(isSelectable()).assertCountEquals(2)
        rule.onNodeWithText("Turn on").performClick()
        // A Not downloaded row and a Failed row open Model download; the other model rows are not buttons.
        rule.onNode(
            hasText("Recognising saved voices", substring = true) and hasClickAction(),
        ).performScrollTo().performClick()
        rule.onNode(
            hasText("Telling speakers apart", substring = true) and hasClickAction(),
        ).performScrollTo().performClick()
        rule.onNode(hasText("Parakeet Ultra", substring = true)).assertHasNoClickAction()
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
                    SettingsEvent.OpenModel(ModelPartUi.SPEAKERS),
                    SettingsEvent.OpenVoices,
                    SettingsEvent.OpenTrash,
                    SettingsEvent.OpenLicenses,
                    SettingsEvent.Back,
                ),
                events,
            )
        }
    }

    /** The row names a language set in Android's per-app setting in its own language ("Français"). */
    @Test fun theRowNamesTheChoiceInItsOwnLanguage() {
        val state = BoardFixtures.settings.copy(language = LanguageChoice.PORTUGUESE_PORTUGAL)
        setThemed { SettingsScreen(state, onEvent = {}) }
        rule.onNode(hasText("Português (Portugal)") and hasClickAction()).assertExists()
    }

    /** The sheet offers Phone and the six app languages, each named in its own language, and selects the current one. */
    @Test fun theSheetOffersEveryAppLanguage() {
        val events = mutableListOf<SettingsEvent>()
        val state = BoardFixtures.settings.copy(language = LanguageChoice.SPANISH)
        setThemed { SettingsScreen(state, onEvent = { events += it }) }
        rule.onNode(hasText("Español") and hasClickAction()).performClick()
        val names = listOf(
            "Phone",
            "English",
            "Italiano",
            "Français",
            "Español",
            "Português (Brasil)",
            "Português (Portugal)",
        )
        val languageOptions = isSelectable() and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        rule.onAllNodes(languageOptions).assertCountEquals(names.size)
        for (name in names) {
            val row = rule.onNode(hasText(name) and isSelectable())
            if (name == "Español") row.assertIsSelected() else row.assertIsNotSelected()
        }
        rule.onNode(hasText("Português (Brasil)") and isSelectable()).performClick()
        rule.runOnIdle { assertEquals(listOf(SettingsEvent.Language(LanguageChoice.PORTUGUESE_BRAZIL)), events) }
    }

    /** A language that is not an app language (German, set with adb) selects no choice; Phone still works. */
    @Test fun anotherLanguageSelectsNoChoice() {
        val events = mutableListOf<SettingsEvent>()
        val state = BoardFixtures.settings.copy(language = LanguageChoice.OTHER)
        setThemed { SettingsScreen(state, onEvent = { events += it }) }
        rule.onNode(hasText("English (United States)") and hasClickAction()).performClick()
        val languageOptions = isSelectable() and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        rule.onAllNodes(languageOptions).assertCountEquals(LanguageChoice.offered.size)
        for (choice in LanguageChoice.offered) rule.onAllNodes(languageOptions)[choice.ordinal].assertIsNotSelected()
        rule.onNode(hasText("Phone") and isSelectable()).performClick()
        rule.runOnIdle { assertEquals(listOf(SettingsEvent.Language(LanguageChoice.PHONE)), events) }
    }

    @Test fun bundledWhistleSelectsTheIncludedModelWithoutADownloadEvent() {
        val events = mutableListOf<SettingsEvent>()
        val state = BoardFixtures.settings.copy(includedAvailable = true, speechChecking = false)
        setThemed { SettingsScreen(state, onEvent = { events += it }) }
        rule.onNodeWithText("Whistle").performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(listOf(SettingsEvent.ChooseSpeech(SpeechModelUi.INCLUDED)), events)
        }
    }

    @Test fun aModelRowReadsItsStatusWithItsName() {
        setThemed { SettingsScreen(BoardFixtures.settings, onEvent = {}) }
        rule.onNode(hasText("Whistle") and hasText("Default") and hasText("Included · 17 MB")).assertExists()
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
