package io.codemyriad.cassini.ui.screens

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.input.ImeAction
import io.codemyriad.cassini.ui.banner.BannerKind
import io.codemyriad.cassini.ui.banner.BannerSpec
import io.codemyriad.cassini.ui.components.TITLE_MAX_LENGTH
import io.codemyriad.cassini.ui.fixtures.BoardFixtures
import io.codemyriad.cassini.ui.fixtures.VariantFixtures
import io.codemyriad.cassini.ui.format.FIXED_ZONE
import io.codemyriad.cassini.ui.format.ProvideFixedFormat
import io.codemyriad.cassini.ui.library.LibraryDialog
import io.codemyriad.cassini.ui.library.LibraryEvent
import io.codemyriad.cassini.ui.library.LibraryScreen
import io.codemyriad.cassini.ui.library.LibrarySheet
import io.codemyriad.cassini.ui.library.LibraryUiState
import io.codemyriad.cassini.ui.note.ExportKind
import io.codemyriad.cassini.ui.note.NoteAction
import io.codemyriad.cassini.ui.note.NoteSheet
import io.codemyriad.cassini.ui.theme.CassiniTheme
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
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

    @Test fun searchFilterMenusSendOnlyTheirOwnSelectionsAndNeverOfferSummary() {
        val filters = io.codemyriad.cassini.ui.library.SearchFiltersUi(speakers = persistentListOf("Ada", "Ben"))
        show(BoardFixtures.library.copy(query = "delivery", searchFilters = filters))
        rule.onAllNodesWithText("Has summary").assertCountEquals(0)
        rule.onNodeWithText("Speaker").performClick()
        rule.onNodeWithText("Ben").performClick()
        assertEvents(LibraryEvent.FilterSpeaker("Ben"))
        rule.onNodeWithText("Any date").performClick()
        rule.onNodeWithText("Past 7 days").performClick()
        assertEvents(
            LibraryEvent.FilterSpeaker("Ben"),
            LibraryEvent.FilterDate(io.codemyriad.cassini.ui.library.SearchDateUi.WEEK),
        )
        rule.onNodeWithText("Recorded by me").performScrollTo().performClick()
        assertEvents(
            LibraryEvent.FilterSpeaker("Ben"),
            LibraryEvent.FilterDate(io.codemyriad.cassini.ui.library.SearchDateUi.WEEK),
            LibraryEvent.ToggleRecordedByMe,
        )
    }

    @Test fun aLargeSpeakerMenuComposesOnlyVisibleRowsAndKeepsAnySpeakerAccessible() {
        val names = (0 until 5000).map { "Guest ${it.toString().padStart(4, '0')}" }
        val filters = io.codemyriad.cassini.ui.library.SearchFiltersUi(speakers = names.toImmutableList())
        show(BoardFixtures.library.copy(query = "delivery", searchFilters = filters))
        rule.onNodeWithText("Speaker").performClick()
        rule.onNodeWithText("Any speaker").assertIsEnabled().assertIsSelected()
        val composed = rule.onAllNodesWithText("Guest ", substring = true).fetchSemanticsNodes().size
        assertTrue("Only the viewport and prefetch rows should compose; got $composed", composed in 1..20)
        rule.onNodeWithTag("speaker-filter-menu").performScrollToIndex(names.size)
        rule.onNodeWithText("Guest 4999").performClick()
        assertEvents(LibraryEvent.FilterSpeaker("Guest 4999"))
        rule.onNodeWithText("Speaker").performClick()
        rule.onNodeWithTag("speaker-filter-menu").performScrollToIndex(0)
        rule.onNodeWithText("Any speaker").assertIsEnabled().performClick()
        assertEvents(LibraryEvent.FilterSpeaker("Guest 4999"), LibraryEvent.FilterSpeaker(null))
    }

    @Test fun filterControlsDisappearWithAnEmptyQuery() {
        show(BoardFixtures.library.copy(query = ""))
        rule.onAllNodesWithText("Speaker").assertCountEquals(0)
        rule.onAllNodesWithText("Any date").assertCountEquals(0)
        rule.onAllNodesWithText("Recorded by me").assertCountEquals(0)
    }

    /** A query state that arrives late does not remove typed text; a clear from the state empties the field. */
    @Test fun theSearchFieldKeepsTypedTextWhileTheStateIsLate() {
        var state by mutableStateOf(BoardFixtures.library)
        setThemed { LibraryScreen(state, onEvent = { events += it }) }
        val field = rule.onNode(hasSetTextAction())
        field.performTextInput("su")
        field.performTextInput("p")
        field.assert(hasText("sup"))
        // The state passes the first keystroke after the field has the second one.
        state = state.copy(query = "su")
        field.assert(hasText("sup"))
        state = state.copy(query = "sup")
        field.assert(hasText("sup"))
        state = state.copy(query = "")
        field.assert(!hasText("sup"))
        assertEvents(LibraryEvent.Query("su"), LibraryEvent.Query("sup"))
    }

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

    /** Ids must be unique (BannerSpec), but a duplicate id still shows both banners and does not crash the list. */
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
