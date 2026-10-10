package org.cassini.ui.screens

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasImeAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.input.ImeAction
import kotlinx.collections.immutable.persistentListOf
import org.cassini.ui.banner.BannerKind
import org.cassini.ui.banner.BannerSpec
import org.cassini.ui.components.TITLE_MAX_LENGTH
import org.cassini.ui.fixtures.BoardFixtures
import org.cassini.ui.fixtures.VariantFixtures
import org.cassini.ui.format.FIXED_ZONE
import org.cassini.ui.format.ProvideFixedFormat
import org.cassini.ui.library.LibraryDialog
import org.cassini.ui.library.LibraryEvent
import org.cassini.ui.library.LibraryScreen
import org.cassini.ui.library.LibrarySheet
import org.cassini.ui.library.LibraryUiState
import org.cassini.ui.note.ExportKind
import org.cassini.ui.note.NoteAction
import org.cassini.ui.note.NoteSheet
import org.cassini.ui.theme.CassiniTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/**
 * What the Library sends (features.md sections 1 and 6): its controls, system back in search mode, the Note actions
 * sheet and the overlays its rows open (subtitle chooser, Document info, the confirmations), and the Rename dialog.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class LibraryBehaviourTest {
    @get:Rule val rule = createComposeRule()

    private lateinit var back: OnBackPressedDispatcher

    private val events = mutableListOf<LibraryEvent>()

    private fun setThemed(content: @Composable () -> Unit) {
        rule.setContent {
            back = requireNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            CassiniTheme(darkTheme = false) {
                ProvideFixedFormat(locale = Locale.ENGLISH, is24Hour = true, zone = FIXED_ZONE, content = content)
            }
        }
    }

    private fun show(state: LibraryUiState) = setThemed { LibraryScreen(state, onEvent = { events += it }) }

    private fun assertEvents(vararg expected: LibraryEvent) = rule.runOnIdle { assertEquals(expected.toList(), events) }

    @Test fun backClearsTheSearchBeforeLeaving() {
        var state by mutableStateOf(VariantFixtures.library.getValue("library-search"))
        setThemed {
            LibraryScreen(state, onEvent = {
                events += it
                if (it == LibraryEvent.ClearQuery) state = state.copy(query = "", results = null)
            })
        }
        rule.runOnIdle { back.onBackPressed() }
        assertEvents(LibraryEvent.ClearQuery)
        // Without a search the Library takes no back: the system leaves the app.
        rule.runOnIdle { assertEquals(false, back.hasEnabledCallbacks()) }
    }

    @Test fun withoutASearchBackIsNotTaken() {
        show(BoardFixtures.library)
        rule.runOnIdle { assertEquals(false, back.hasEnabledCallbacks()) }
    }

    @Test fun theControlsSendTheirEvents() {
        show(BoardFixtures.library)
        rule.onNodeWithContentDescription("Record a new note").performClick()
        rule.onNodeWithText("Import audio").performClick()
        rule.onNodeWithText("Transcribing Weekly product sync", useUnmergedTree = true).performClick()
        assertEvents(LibraryEvent.Record, LibraryEvent.Import, LibraryEvent.OpenProgress)
    }

    @Test fun duringARecordingTheCardAndTheButtonReturnToIt() {
        show(VariantFixtures.library.getValue("library-recording-card"))
        rule.onNode(hasContentDescription("Return to recording") and hasClickAction()).performClick()
        rule.onNodeWithText("Recording · Standup", substring = true).performClick()
        assertEvents(LibraryEvent.ReturnToRecording, LibraryEvent.ReturnToRecording)
    }

    @Test fun undoAnswersTheSnackbar() {
        show(VariantFixtures.library.getValue("library-snackbar"))
        rule.onNodeWithText("Undo").performClick()
        assertEvents(LibraryEvent.Undo)
    }

    /** BannerSpec.id defaults to the kind, so two imports in a row give two banners with one id. */
    @Test fun twoBannersOfOneKindShowSideBySide() {
        val importing = BannerSpec(BannerKind.IMPORTING)
        show(BoardFixtures.library.copy(banners = persistentListOf(importing, importing)))
        rule.onAllNodesWithText("Importing", substring = true).assertCountEquals(2)
    }

    @Test fun theActionsSheetOpensInItsWindowAndSendsItsRows() {
        show(BoardFixtures.library.copy(sheet = LibrarySheet.Actions(BoardFixtures.noteActions)))
        rule.onNodeWithText("Subtitles", substring = true).performClick()
        rule.onNodeWithText("Document info").performClick()
        assertEvents(
            LibraryEvent.SheetAction(NoteAction.SAVE_SUBTITLES),
            LibraryEvent.SheetAction(NoteAction.DOCUMENT_INFO),
        )
    }

    @Test fun theSubtitleChooserExportsTheNote() {
        show(BoardFixtures.library.copy(sheet = LibrarySheet.SubtitleFormat(BoardFixtures.supplier.id)))
        rule.onNodeWithText("SubRip (.srt)").performClick()
        rule.onNodeWithText("WebVTT (.vtt)").performClick()
        assertEvents(
            LibraryEvent.Export(BoardFixtures.supplier.id, ExportKind.SRT),
            LibraryEvent.Export(BoardFixtures.supplier.id, ExportKind.VTT),
        )
    }

    @Test fun documentInfoOpensAsADialog() {
        val info = VariantFixtures.noteSheets.getValue("read-document-info").sheet as NoteSheet.Info
        show(BoardFixtures.library.copy(sheet = LibrarySheet.Info(BoardFixtures.supplier.id, info.ui)))
        rule.onNodeWithText("Document info").assertExists()
        rule.onNodeWithText("Close").performClick()
        assertEvents(LibraryEvent.DismissSheet)
    }

    @Test fun theConfirmationsOfTheSheetRows() {
        var dialog by mutableStateOf<LibraryDialog>(LibraryDialog.TranscribeFresh(BoardFixtures.supplier.id))
        setThemed { LibraryScreen(BoardFixtures.library.copy(dialog = dialog), onEvent = { events += it }) }
        rule.onNodeWithText("Earlier versions can", substring = true).assertExists()
        rule.onNode(hasText("Transcribe again") and hasClickAction()).performClick()
        dialog = LibraryDialog.StartOver(BoardFixtures.supplier.id)
        rule.onNodeWithText("Start over?").assertExists()
        rule.onNode(hasText("Start over") and hasClickAction()).performClick()
        dialog = LibraryDialog.StopAndTrash(BoardFixtures.supplier.id)
        rule.onNodeWithText("Stop transcription and move to trash?").assertExists()
        rule.onNodeWithText("Cancel").performClick()
        assertEvents(LibraryEvent.ConfirmDialog, LibraryEvent.ConfirmDialog, LibraryEvent.DismissDialog)
    }

    @Test fun renameSavesTheTrimmedTitleOnlyWhenItChanged() {
        val supplier = BoardFixtures.supplier
        show(BoardFixtures.library.copy(dialog = LibraryDialog.Rename(supplier.id, supplier.title)))
        val field = rule.onNode(hasSetTextAction() and hasImeAction(ImeAction.Done))
        val save = rule.onNode(hasText("Save") and hasClickAction())
        save.assertIsNotEnabled()
        field.performTextReplacement("   ")
        rule.onNodeWithText("Enter a title.").assertExists()
        save.assertIsNotEnabled()
        field.performTextReplacement("  ${supplier.title} ")
        save.assertIsNotEnabled()
        field.performTextReplacement("  Supplier call, final  ")
        rule.onNodeWithText("Enter a title.").assertDoesNotExist()
        save.assertIsEnabled().performClick()
        assertEvents(LibraryEvent.RenameConfirmed(supplier.id, "Supplier call, final"))
    }

    @Test fun renameStopsAtTheTitleLimit() {
        val supplier = BoardFixtures.supplier
        show(BoardFixtures.library.copy(dialog = LibraryDialog.Rename(supplier.id, supplier.title)))
        val field = rule.onNode(hasSetTextAction() and hasImeAction(ImeAction.Done))
        field.performTextReplacement("a".repeat(TITLE_MAX_LENGTH + 50))
        rule.onNode(hasText("Save") and hasClickAction()).performClick()
        assertEvents(LibraryEvent.RenameConfirmed(supplier.id, "a".repeat(TITLE_MAX_LENGTH)))
    }

    /**
     * At font scale 2 during a recording the Record label wraps and the bar grows past its board height; the list
     * still scrolls its last row clear of the bar.
     */
    @Test
    @Config(fontScale = 2f)
    fun theLastRowScrollsClearOfATallBottomBar() {
        val state = VariantFixtures.library.getValue("library-recording-card")
        show(state)
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(lastIndex(state))
        rule.waitForIdle()
        val last = state.sections.last().rows.last()
        val row = rule.onNode(hasContentDescription(last.title, substring = true) and hasClickAction())
            .getBoundsInRoot()
        val fab = rule.onNode(hasContentDescription("Return to recording") and hasClickAction()).getBoundsInRoot()
        assertTrue("row bottom ${row.bottom} is under the bar (Record top ${fab.top})", row.bottom <= fab.top)
    }

    /** The index of the last list item: header, search, activity items, the list top spacer, headers and rows. */
    private fun lastIndex(state: LibraryUiState): Int {
        val activity = listOfNotNull(state.recording, state.progress).size + state.banners.size
        val rows = state.sections.sumOf { it.rows.size + 1 }
        return 2 + activity + 1 + rows - 1
    }
}
