package org.cassini.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.cassini.ui.fixtures.BoardFixtures
import org.cassini.ui.fixtures.VariantFixtures
import org.cassini.ui.format.FIXED_ZONE
import org.cassini.ui.format.ProvideFixedFormat
import org.cassini.ui.note.ExportKind
import org.cassini.ui.note.MergeConfirmContent
import org.cassini.ui.note.MergeEvent
import org.cassini.ui.note.MergeSheetContent
import org.cassini.ui.note.MergeVersionsUi
import org.cassini.ui.note.NameSheetEvent
import org.cassini.ui.note.NameSpeakerSheetContent
import org.cassini.ui.note.NoteAction
import org.cassini.ui.note.NoteActionsSheetContent
import org.cassini.ui.note.NoteDialog
import org.cassini.ui.note.NoteEvent
import org.cassini.ui.note.NoteScreen
import org.cassini.ui.note.NoteSheet
import org.cassini.ui.note.SaveState
import org.cassini.ui.note.SubtitleFormatSheetContent
import org.cassini.ui.note.VersionsSheetContent
import org.cassini.ui.theme.CassiniTheme
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

    @Test fun theNameSheetControlsSendTheirEvents() {
        val events = mutableListOf<NameSheetEvent>()
        setThemed { NameSpeakerSheetContent(BoardFixtures.nameSpeaker, onEvent = { events += it }) }
        rule.onNodeWithContentDescription("Play a sample of this voice").performClick()
        rule.onNodeWithText("Annalisa B.").performClick()
        rule.onNodeWithText("Same person").performClick()
        rule.onNodeWithText("Someone else").performClick()
        rule.onNodeWithText("Remember this voice on this phone", substring = true).performClick()
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
        rule.onNodeWithText("Cassini document").performClick()
        rule.onNodeWithText("Subtitles", substring = true).performClick()
        rule.onNodeWithText("Rename").performClick()
        rule.onNodeWithText("Transcribe again").performClick()
        rule.onNodeWithText("Document info").performClick()
        rule.onNodeWithText("Move to trash").performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(
                listOf(
                    NoteAction.SAVE_DOCUMENT,
                    NoteAction.SAVE_SUBTITLES,
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
        rule.onNodeWithContentDescription("Share Cassini document").performClick()
        rule.onNodeWithContentDescription("Share Transcript as text").performClick()
        rule.onNodeWithContentDescription("Share Subtitles").performClick()
        rule.runOnIdle {
            assertEquals(listOf(NoteAction.SHARE_DOCUMENT, NoteAction.SHARE_TEXT, NoteAction.SHARE_SUBTITLES), actions)
        }
    }

    @Test fun aRowThatCannotExportCannotShare() {
        setThemed {
            NoteActionsSheetContent(VariantFixtures.noteActions.getValue("actions-plain"), onAction = {})
        }
        rule.onNodeWithContentDescription("Share Audio file").assertIsEnabled()
        rule.onNodeWithContentDescription("Share Transcript as text").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Share Subtitles").assertIsNotEnabled()
    }

    @Test fun aBusyRowCannotShare() {
        setThemed { NoteActionsSheetContent(VariantFixtures.noteActions.getValue("actions-busy"), onAction = {}) }
        rule.onNodeWithContentDescription("Share Transcript as text").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Share Cassini document").assertIsEnabled()
    }

    @Test fun aPlainNoteDisablesTextExportAndHasNoDocumentInfo() {
        val actions = mutableListOf<NoteAction>()
        setThemed {
            NoteActionsSheetContent(VariantFixtures.noteActions.getValue("actions-plain"), onAction = { actions += it })
        }
        rule.onNodeWithText("Audio file").assertIsEnabled()
        rule.onNode(hasText("Transcript as text", substring = true).and(hasClickAction())).assertIsNotEnabled()
        rule.onNodeWithText("Document info").assertDoesNotExist()
        rule.onNodeWithText("Transcribe").performClick()
        rule.runOnIdle { assertEquals(listOf(NoteAction.TRANSCRIBE), actions) }
    }

    @Test fun theBusyRowReportsSaving() {
        setThemed {
            NoteActionsSheetContent(VariantFixtures.noteActions.getValue("actions-busy"), onAction = {})
        }
        rule.onNode(hasText("Transcript as text", substring = true).and(hasClickAction()))
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
        rule.onNodeWithText("Resume").performClick()
        rule.onNodeWithText("Start over").performClick()
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

    @Test fun theSubtitleChooserExportsEitherFormat() {
        val kinds = mutableListOf<ExportKind>()
        setThemed { SubtitleFormatSheetContent(onExport = { kinds += it }) }
        rule.onNodeWithText("WebVTT (.vtt)").performClick()
        rule.onNodeWithText("SubRip (.srt)").performClick()
        rule.runOnIdle { assertEquals(listOf(ExportKind.VTT, ExportKind.SRT), kinds) }
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
        rule.onNodeWithText("Transcribe again").performClick()
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
