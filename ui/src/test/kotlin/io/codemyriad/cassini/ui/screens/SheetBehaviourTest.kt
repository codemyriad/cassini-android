package io.codemyriad.cassini.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import io.codemyriad.cassini.ui.fixtures.BoardFixtures
import io.codemyriad.cassini.ui.fixtures.VariantFixtures
import io.codemyriad.cassini.ui.format.FIXED_ZONE
import io.codemyriad.cassini.ui.format.ProvideFixedFormat
import io.codemyriad.cassini.ui.note.MergeConfirmContent
import io.codemyriad.cassini.ui.note.MergeEvent
import io.codemyriad.cassini.ui.note.MergeSheetContent
import io.codemyriad.cassini.ui.note.MergeVersionsUi
import io.codemyriad.cassini.ui.note.NameSheetEvent
import io.codemyriad.cassini.ui.note.NameSpeakerSheetContent
import io.codemyriad.cassini.ui.note.NoteAction
import io.codemyriad.cassini.ui.note.NoteActionsSheetContent
import io.codemyriad.cassini.ui.note.NoteDialog
import io.codemyriad.cassini.ui.note.NoteEvent
import io.codemyriad.cassini.ui.note.NoteScreen
import io.codemyriad.cassini.ui.note.NoteSheet
import io.codemyriad.cassini.ui.note.SaveState
import io.codemyriad.cassini.ui.note.VersionsSheetContent
import io.codemyriad.cassini.ui.theme.CassiniTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/**
 * What the sheets and dialogs send (features.md sections 3.4, 4 and 6): the Name sheet's controls and its blocked
 * Save, the Note actions rows in their forms, the Versions radios, the Merge rows and confirmation, the subtitle
 * chooser, and the Note screen opening each overlay in its own window.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class SheetBehaviourTest {
    @get:Rule val rule = createComposeRule()

    private val answerFirst =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Answer the question above first")

    private fun setThemed(content: @Composable () -> Unit) {
        rule.setContent {
            CassiniTheme(darkTheme = false) {
                ProvideFixedFormat(locale = Locale.ENGLISH, is24Hour = true, zone = FIXED_ZONE, content = content)
            }
        }
    }

    @Test fun saveWithTheQuestionOpenMovesFocusToTheQuestion() {
        val events = mutableListOf<NameSheetEvent>()
        setThemed { NameSpeakerSheetContent(BoardFixtures.nameSpeaker, onEvent = { events += it }) }
        rule.onNodeWithText("Save name")
            .assertIsEnabled()
            .assert(answerFirst)
            .performClick()
        rule.runOnIdle { assertEquals(listOf(NameSheetEvent.SaveBlocked), events) }
        rule.onNode(isFocused()).assert(answerFirst)
    }

    /**
     * Every blocked press says "Answer the question above first" again: the question card's polite live region clears
     * its state description and sets it again, so TalkBack sees a change on each press (features.md section 4).
     */
    @Test fun everyBlockedSaveAnnouncesTheQuestionAgain() {
        val events = mutableListOf<NameSheetEvent>()
        setThemed { NameSpeakerSheetContent(BoardFixtures.nameSpeaker, onEvent = { events += it }) }
        // The question card: the sheet's only focusable live region.
        val card = SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Focused)
        rule.onNode(card).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        rule.mainClock.autoAdvance = false
        repeat(2) {
            rule.onNodeWithText("Save name").performClick()
            rule.mainClock.advanceTimeBy(FRAME_MS)
            rule.onNode(card).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
            rule.mainClock.advanceTimeBy(ANNOUNCE_MS)
            rule.onNode(card).assert(answerFirst)
        }
        rule.mainClock.autoAdvance = true
        rule.runOnIdle { assertEquals(listOf(NameSheetEvent.SaveBlocked, NameSheetEvent.SaveBlocked), events) }
    }

    @Test fun rememberHasOneNativeSwitchTargetAndKeepsTheTwoLinePrivacyCopy() {
        val events = mutableListOf<NameSheetEvent>()
        setThemed { NameSpeakerSheetContent(BoardFixtures.nameSpeaker, onEvent = { events += it }) }
        val switch = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
        rule.onAllNodes(switch).assertCountEquals(1)
        rule.onNode(switch).assertIsOn().assertIsEnabled().performClick()
        rule.onNodeWithText(
            "Anna is named automatically next time.\nThe voiceprint stays on this phone.",
            substring = true,
        )
            .assertExists()
        rule.runOnIdle { assertEquals(listOf(NameSheetEvent.RememberChanged(false)), events) }
    }

    @Test fun disabledRememberDoesNotSendAToggleAndQuoteComesFromTheSelectedSpeaker() {
        val events = mutableListOf<NameSheetEvent>()
        val ui = BoardFixtures.nameSpeaker.copy(rememberEnabled = false, rememberReason = null)
        setThemed { NameSpeakerSheetContent(ui, onEvent = { events += it }) }
        rule.onNodeWithTag("remember-voice").assertIsNotEnabled().performClick()
        rule.onNodeWithText("“${ui.quote}”").assertExists()
        rule.onNodeWithText(
            "Anna is named automatically next time.\nThe voiceprint stays on this phone.",
            substring = true,
        )
            .assertExists()
        rule.runOnIdle { assertEquals(emptyList<NameSheetEvent>(), events) }
    }

    @Test fun theNameSheetControlsSendTheirEvents() {
        val events = mutableListOf<NameSheetEvent>()
        setThemed { NameSpeakerSheetContent(BoardFixtures.nameSpeaker, onEvent = { events += it }) }
        rule.onNodeWithContentDescription("Play a sample of this voice").performClick()
        rule.onNodeWithText("Annalisa B.").performClick()
        rule.onNodeWithTag("name-primary-pill", useUnmergedTree = true).assertHeightIsAtLeast(32.dp)
        rule.onNodeWithText("Same person").assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithText("Someone else").performClick()
        rule.onNodeWithText("Remember this voice", substring = true).performClick()
        rule.onNodeWithText("Merge into…").performClick()
        rule.onNodeWithText("Cancel").performClick()
        rule.runOnIdle {
            assertEquals(
                listOf(
                    NameSheetEvent.PlaySample,
                    NameSheetEvent.PickVoice("annalisa"),
                    NameSheetEvent.SamePerson,
                    NameSheetEvent.SomeoneElse,
                    NameSheetEvent.RememberChanged(false),
                    NameSheetEvent.MergeInto,
                    NameSheetEvent.Cancel,
                ),
                events,
            )
        }
    }

    @Test fun saveSavesWhenEnabledAndNotWhenDisabled() {
        val events = mutableListOf<NameSheetEvent>()
        val draft = VariantFixtures.nameSheet.getValue("name-draft")
        setThemed { NameSpeakerSheetContent(draft, onEvent = { events += it }) }
        rule.onNodeWithText("Save name").performClick()
        rule.onNodeWithContentDescription("Play a sample of this voice").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(listOf(NameSheetEvent.Save), events) }
    }

    @Test fun aDisabledSaveAndShowAllAndNotName() {
        val events = mutableListOf<NameSheetEvent>()
        val showAll = VariantFixtures.nameSheet.getValue("name-show-all")
        assertEquals(SaveState.DISABLED, showAll.save)
        setThemed { NameSpeakerSheetContent(showAll.copy(notName = "Anna Ricci"), onEvent = { events += it }) }
        rule.onNodeWithText("Save name").assertIsNotEnabled()
        rule.onNodeWithText("Show all").performClick()
        rule.onNodeWithText("Not Anna Ricci?").performClick()
        rule.runOnIdle { assertEquals(listOf(NameSheetEvent.ShowAll, NameSheetEvent.NotName), events) }
    }

    @Test fun noteActionRowsSendTheirAction() {
        val actions = mutableListOf<NoteAction>()
        setThemed { NoteActionsSheetContent(BoardFixtures.noteActions, onAction = { actions += it }) }
        rule.onNodeWithText("Cassini file").performClick()
        rule.onNodeWithText("Markdown", substring = true).performClick()
        rule.onNodeWithText("More formats").performClick()
        rule.onNodeWithText("Plain text", substring = true).performScrollTo().performClick()
        rule.onNodeWithText("Subtitles (SRT)", substring = true).performScrollTo().performClick()
        rule.onNodeWithText("Subtitles (WebVTT)", substring = true).performScrollTo().performClick()
        rule.onNodeWithText("Rename").performScrollTo().performClick()
        rule.onNodeWithText("Transcribe again").performScrollTo().performClick()
        rule.onNodeWithText("Document info").performScrollTo().performClick()
        rule.onNodeWithText("Move to trash").performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(
                listOf(
                    NoteAction.SAVE_DOCUMENT,
                    NoteAction.SAVE_MARKDOWN,
                    NoteAction.SAVE_TEXT,
                    NoteAction.SAVE_SRT,
                    NoteAction.SAVE_VTT,
                    NoteAction.RENAME,
                    NoteAction.TRANSCRIBE_AGAIN,
                    NoteAction.DOCUMENT_INFO,
                    NoteAction.MOVE_TO_TRASH,
                ),
                actions,
            )
        }
    }

    /** Each "Save a copy" row has its own Share button (D53), spoken as "Share" and the row's name. */
    @Test fun theShareButtonsSendTheirShareAction() {
        val actions = mutableListOf<NoteAction>()
        setThemed { NoteActionsSheetContent(BoardFixtures.noteActions, onAction = { actions += it }) }
        rule.onNodeWithContentDescription("Share Cassini file").performClick()
        rule.onNodeWithContentDescription("Share Markdown").performClick()
        rule.onNodeWithText("More formats").performClick()
        rule.onNodeWithContentDescription("Share Plain text").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Share Subtitles (SRT)").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Share Subtitles (WebVTT)").performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(
                listOf(
                    NoteAction.SHARE_DOCUMENT,
                    NoteAction.SHARE_MARKDOWN,
                    NoteAction.SHARE_TEXT,
                    NoteAction.SHARE_SRT,
                    NoteAction.SHARE_VTT,
                ),
                actions,
            )
        }
    }

    /** L-11 (D99): Cassini file and Markdown first; the other text formats wait under More formats. */
    @Test fun moreFormatsStartsClosedAndToggles() {
        setThemed { NoteActionsSheetContent(BoardFixtures.noteActions, onAction = {}) }
        rule.onNodeWithText("Plain text", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Subtitles (SRT)", substring = true).assertDoesNotExist()
        rule.onNodeWithText("More formats").assert(SemanticsMatcher.keyIsDefined(SemanticsActions.Expand))
        rule.onNodeWithText("More formats").performClick()
        rule.onNodeWithText("Plain text", substring = true).assertExists()
        rule.onNodeWithText("Subtitles (WebVTT)", substring = true).assertExists()
        rule.onNodeWithText("More formats").assert(SemanticsMatcher.keyIsDefined(SemanticsActions.Collapse))
        rule.onNodeWithText("More formats").performClick()
        rule.onNodeWithText("Plain text", substring = true).assertDoesNotExist()
    }

    /** An audio-only meeting hides the text formats (not greyed out) and says why (L-11, D99). */
    @Test fun anAudioOnlyNoteHidesTheTextFormats() {
        setThemed {
            NoteActionsSheetContent(VariantFixtures.noteActions.getValue("actions-plain"), onAction = {})
        }
        rule.onNodeWithContentDescription("Share Audio file").assertIsEnabled()
        rule.onNodeWithText("No transcript yet, so only the audio can be exported.").assertExists()
        rule.onNodeWithText("Markdown", substring = true).assertDoesNotExist()
        rule.onNodeWithText("More formats").assertDoesNotExist()
        rule.onNodeWithText("Plain text", substring = true).assertDoesNotExist()
    }

    /** Imported audio without a Cassini file offers its Original audio in place of the Cassini file (L-11, D99). */
    @Test fun importedAudioOffersOriginalAudio() {
        val actions = mutableListOf<NoteAction>()
        setThemed {
            NoteActionsSheetContent(
                VariantFixtures.noteActions.getValue("actions-imported-audio"),
                onAction = { actions += it },
            )
        }
        rule.onNodeWithText("Original audio").performClick()
        val body = "The audio exactly as it was imported, without a transcript."
        rule.onNodeWithText(body, substring = true).assertExists()
        rule.onNodeWithText("Cassini file").assertDoesNotExist()
        rule.onNodeWithText("No transcript yet, so only the audio can be exported.").assertExists()
        rule.runOnIdle { assertEquals(listOf(NoteAction.SAVE_DOCUMENT), actions) }
    }

    @Test fun anUnreadableTranscriptSaysSoInsteadOfTheTextFormats() {
        setThemed {
            NoteActionsSheetContent(VariantFixtures.noteActions.getValue("actions-unreadable"), onAction = {})
        }
        rule.onNodeWithText("Cassini file").assertIsEnabled()
        rule.onNodeWithText("Not available for this transcript").assertExists()
        rule.onNodeWithText("More formats").assertDoesNotExist()
    }

    @Test fun aBusyRowCannotShare() {
        setThemed { NoteActionsSheetContent(VariantFixtures.noteActions.getValue("actions-busy"), onAction = {}) }
        rule.onNodeWithContentDescription("Share Plain text").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Share Cassini file").assertIsEnabled()
    }

    @Test fun aPlainNoteHidesTextExportAndHasNoDocumentInfo() {
        val actions = mutableListOf<NoteAction>()
        setThemed {
            NoteActionsSheetContent(VariantFixtures.noteActions.getValue("actions-plain"), onAction = { actions += it })
        }
        rule.onNodeWithText("Audio file").assertIsEnabled()
        rule.onNode(hasText("Plain text", substring = true).and(hasClickAction())).assertDoesNotExist()
        rule.onNodeWithText("Document info").assertDoesNotExist()
        rule.onNodeWithText("Transcribe").performClick()
        rule.runOnIdle { assertEquals(listOf(NoteAction.TRANSCRIBE), actions) }
    }

    @Test fun theBusyRowReportsSaving() {
        setThemed {
            NoteActionsSheetContent(VariantFixtures.noteActions.getValue("actions-busy"), onAction = {})
        }
        rule.onNode(hasText("Plain text", substring = true).and(hasClickAction()))
            .assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Saving…"))
    }

    @Test fun aNoteBeingRecordedCannotMoveToTrash() {
        setThemed {
            NoteActionsSheetContent(VariantFixtures.noteActions.getValue("actions-recording"), onAction = {})
        }
        rule.onNodeWithText("Rename").assertExists()
        rule.onNodeWithText("Move to trash").assertDoesNotExist()
    }

    @Test fun pausedRowsResumeOrStartOver() {
        val actions = mutableListOf<NoteAction>()
        setThemed {
            NoteActionsSheetContent(
                VariantFixtures.noteActions.getValue("actions-resume-or-start-over"),
                onAction = { actions += it },
            )
        }
        rule.onNodeWithText("Resume").performScrollTo().performClick()
        rule.onNodeWithText("Start over").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(listOf(NoteAction.RESUME, NoteAction.START_OVER), actions) }
    }

    @Test fun versionsPickReadableRowsOnly() {
        val picks = mutableListOf<Int>()
        setThemed { VersionsSheetContent(VariantFixtures.versions, onPick = { picks += it }) }
        rule.onNodeWithText("Version 2", substring = true).assertIsSelected()
        rule.onNodeWithText("Version 1", substring = true).assertIsNotSelected().performClick()
        rule.onNodeWithText("Version 3", substring = true).assertIsNotEnabled()
        rule.runOnIdle { assertEquals(listOf(0), picks) }
    }

    @Test fun mergeRowsAndConfirmation() {
        val events = mutableListOf<MergeEvent>()
        val merge = VariantFixtures.merges.getValue("merge-confirm-sealed")
        setThemed {
            Column {
                MergeSheetContent(merge, onEvent = { events += it })
                MergeConfirmContent(merge, requireNotNull(merge.confirm), onEvent = { events += it })
            }
        }
        rule.onNodeWithText("Speaker 4").performClick()
        rule.onNodeWithText("Merge Speaker 3 into Paolo Ferri?").assertExists()
        rule.onNodeWithText(
            "Everything Speaker 3 said will be shown as Paolo Ferri. This adds version 3; version 2 stays available.",
        ).assertExists()
        rule.onNodeWithText("Merge").performClick()
        rule.runOnIdle { assertEquals(listOf(MergeEvent.PickTarget("s4"), MergeEvent.Confirm), events) }
    }

    /** On version 1 of 2 a merge adds version 3, not 2 (features.md section 4, item 8). */
    @Test fun aMergeOnAnEarlierVersionNamesTheNewLastVersion() {
        val sealed = VariantFixtures.merges.getValue("merge-confirm-sealed")
        val merge = sealed.copy(sealed = MergeVersionsUi(shown = 1, added = 3))
        setThemed { MergeConfirmContent(merge, requireNotNull(merge.confirm), onEvent = {}) }
        rule.onNodeWithText("This adds version 3; version 1 stays available.", substring = true).assertExists()
    }

    @Test fun theNoteScreenOpensTheSheetAndTheDialogInWindows() {
        val events = mutableListOf<NoteEvent>()
        val state = BoardFixtures.readAndListen.copy(
            sheet = NoteSheet.Actions(BoardFixtures.noteActions),
            dialog = NoteDialog.StartOver,
        )
        setThemed { NoteScreen(state, onEvent = { events += it }) }
        rule.onNodeWithText("Start over?").assertExists()
        rule.onNodeWithText("Start over").performClick()
        rule.onNodeWithText("Transcribe again").performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(
                listOf(NoteEvent.ConfirmDialog, NoteEvent.SheetAction(NoteAction.TRANSCRIBE_AGAIN)),
                events,
            )
        }
    }

    private companion object {
        const val FRAME_MS = 32L
        const val ANNOUNCE_MS = 400L
    }
}
