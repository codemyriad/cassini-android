package org.cassini.ui.screens

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasImeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.input.ImeAction
import org.cassini.ui.fixtures.BoardFixtures
import org.cassini.ui.fixtures.VariantFixtures
import org.cassini.ui.format.FIXED_ZONE
import org.cassini.ui.format.ProvideFixedFormat
import org.cassini.ui.note.ExportKind
import org.cassini.ui.note.MergeEvent
import org.cassini.ui.note.NameSheetEvent
import org.cassini.ui.note.NoteDialog
import org.cassini.ui.note.NoteEvent
import org.cassini.ui.note.NoteScreen
import org.cassini.ui.note.NoteSheet
import org.cassini.ui.note.NoteUiState
import org.cassini.ui.note.ReadOnlyReason
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
 * The Note screen's own overlay mapping (NoteOverlays): each sheet and dialog opens in its window from the screen
 * state, and its controls reach the screen as the wrapped [NoteEvent]. The goldens draw the overlay content inline
 * (NoteOverlayFrame), so only these tests check the wrappers and the dismiss callbacks.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class NoteOverlaysBehaviourTest {
    @get:Rule val rule = createComposeRule()

    private val events = mutableListOf<NoteEvent>()

    private fun show(state: NoteUiState) {
        rule.setContent {
            CassiniTheme(darkTheme = false) {
                ProvideFixedFormat(locale = Locale.ENGLISH, is24Hour = true, zone = FIXED_ZONE) {
                    NoteScreen(state, onEvent = { events += it })
                }
            }
        }
    }

    private fun withSheet(sheet: NoteSheet) = BoardFixtures.readAndListen.copy(sheet = sheet)

    private fun assertEvents(vararg expected: NoteEvent) = rule.runOnIdle { assertEquals(expected.toList(), events) }

    @Test fun theNameSheetWrapsItsEvents() {
        show(withSheet(NoteSheet.NameSpeaker(BoardFixtures.nameSpeaker)))
        rule.onNodeWithText("Save name").performClick()
        rule.onNodeWithText("Cancel").performClick()
        assertEvents(NoteEvent.NameSheet(NameSheetEvent.SaveBlocked), NoteEvent.NameSheet(NameSheetEvent.Cancel))
    }

    @Test fun theMergeSheetPicksATarget() {
        show(withSheet(NoteSheet.Merge(VariantFixtures.merges.getValue("merge-sheet"))))
        rule.onNodeWithText("Speaker 4").performClick()
        assertEvents(NoteEvent.Merge(MergeEvent.PickTarget("s4")))
    }

    @Test fun theMergeConfirmationOpensOverTheSheet() {
        show(withSheet(NoteSheet.Merge(VariantFixtures.merges.getValue("merge-confirm-sealed"))))
        rule.onNodeWithText("Merge Speaker 3 into Paolo Ferri?").assertExists()
        rule.onNode(hasText("Merge") and hasClickAction()).performClick()
        rule.onNodeWithText("Cancel").performClick()
        assertEvents(NoteEvent.Merge(MergeEvent.Confirm), NoteEvent.Merge(MergeEvent.CancelConfirm))
    }

    @Test fun theVersionsSheetPicksAVersion() {
        show(withSheet(NoteSheet.Versions(VariantFixtures.versions)))
        rule.onNodeWithText("Version 1", substring = true).performClick()
        assertEvents(NoteEvent.PickVersion(0))
    }

    @Test fun theSubtitleChooserExports() {
        show(withSheet(NoteSheet.SubtitleFormat))
        rule.onNodeWithText("SubRip (.srt)").performClick()
        assertEvents(NoteEvent.Export(ExportKind.SRT))
    }

    @Test fun documentInfoClosesTheSheet() {
        show(withSheet(NoteSheet.Info(VariantFixtures.info)))
        rule.onNodeWithText("Close").performClick()
        assertEvents(NoteEvent.DismissSheet)
    }

    @Test fun renameConfirmsTheNewTitle() {
        show(BoardFixtures.readAndListen.copy(dialog = NoteDialog.Rename(BoardFixtures.readAndListen.title)))
        rule.onNode(hasSetTextAction() and hasImeAction(ImeAction.Done)).performTextReplacement("Supplier call")
        rule.onNode(hasText("Save") and hasClickAction()).performClick()
        assertEvents(NoteEvent.RenameConfirmed("Supplier call"))
    }

    @Test fun aReadOnlyNoticeClosesAsADialog() {
        show(BoardFixtures.readAndListen.copy(dialog = NoteDialog.ReadOnly(ReadOnlyReason.JOB_PENDING)))
        rule.onNodeWithText("Wait until transcription finishes").assertExists()
        rule.onNodeWithText("Close").performClick()
        assertEvents(NoteEvent.DismissDialog)
    }

    @Test fun theStopAndTrashConfirmationConfirms() {
        show(BoardFixtures.transcribing.copy(dialog = NoteDialog.StopAndTrash))
        rule.onNode(hasText("Move to trash") and hasClickAction()).performClick()
        assertEvents(NoteEvent.ConfirmDialog)
    }
}
