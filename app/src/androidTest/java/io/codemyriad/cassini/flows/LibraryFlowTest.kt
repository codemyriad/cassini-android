package io.codemyriad.cassini.flows

import android.os.SystemClock
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import io.codemyriad.cassini.MainActivity
import io.codemyriad.cassini.background.Background
import io.codemyriad.cassini.core.library.AudioKind
import io.codemyriad.cassini.core.library.JobState
import io.codemyriad.cassini.data.db.LibraryRow
import io.codemyriad.cassini.flows.Flows.awaitUi
import io.codemyriad.cassini.flows.Flows.backAfterOpenWith
import io.codemyriad.cassini.flows.Flows.closeKeyboard
import io.codemyriad.cassini.flows.Flows.import
import io.codemyriad.cassini.flows.Flows.logScreen
import io.codemyriad.cassini.flows.Flows.meetingDocument
import io.codemyriad.cassini.flows.Flows.placementReport
import io.codemyriad.cassini.flows.Flows.row
import io.codemyriad.cassini.flows.Flows.rows
import io.codemyriad.cassini.flows.Flows.text
import io.codemyriad.cassini.flows.Flows.view
import io.codemyriad.cassini.flows.Flows.waitGone
import io.codemyriad.cassini.flows.Flows.waitKeyboardClosed
import io.codemyriad.cassini.flows.Flows.waitShown
import io.codemyriad.cassini.flows.Flows.waitText
import io.codemyriad.cassini.test.CiSmoke
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import io.codemyriad.cassini.ui.R as UiR

/**
 * The Library on the device (features.md section 1): the empty state; the FLEURS clip opened through ACTION_VIEW
 * from a `file://` copy in the scratch cache opens its note with the Library under it, and a second ACTION_VIEW of
 * the same file opens the same note; the row is Audio only (or Queued); search finds a spoken word of another note;
 * a long press opens the actions sheet, which closes the search keyboard; Move to trash shows the snackbar and Undo
 * restores the note. The flow closes the search keyboard before the long press, so that its taps do not race the
 * keyboard; [actionsOpenedWithTheSearchKeyboardStillMoveToTrash] opens the sheet with the keyboard up, as a user does.
 */
@CiSmoke
class LibraryFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Before fun setUp() = runBlocking {
        Background.grant()
        Flows.graph.ready.await()
        Background.reset()
    }

    @After fun tearDown() = runBlocking { Background.reset() }

    @Test fun importSearchActionsTrashAndUndo() {
        rule.waitText(rule.text(UiR.string.ui_banner_lib_empty_title))

        // ACTION_VIEW: the note opens over the Library.
        val clip = Background.assetCopy("fleurs-it-short.wav")
        rule.view(clip, "audio/wav")
        val noteId = awaitRows("The import makes one note") { it.size == 1 }.single().id
        rule.waitShown(hasContentDescription(rule.text(UiR.string.ui_back_to_library)))
        rule.waitText(CLIP_TITLE)
        rule.backAfterOpenWith()
        val row = hasContentDescription(CLIP_TITLE, substring = true)
        rule.waitShown(row)
        val status = runBlocking { row(CLIP_TITLE) }!!
        assertEquals(AudioKind.PLAIN, status.audioKind)
        val queued = status.jobState == JobState.QUEUED
        assertTrue("Audio only or Queued: ${status.jobState}", status.jobState == null || queued)
        val chip = rule.text(if (queued) UiR.string.ui_status_queued else UiR.string.ui_status_audio_only)
        rule.waitShown(hasContentDescription(chip, substring = true, ignoreCase = true))

        // The same file again: the same note, again with the Library under it.
        rule.view(clip, "audio/wav")
        rule.waitShown(hasContentDescription(rule.text(UiR.string.ui_back_to_library)))
        rule.backAfterOpenWith()
        rule.waitShown(row)
        val ids = runBlocking { rows().map { it.id } }
        assertEquals("A second ACTION_VIEW of the same file keeps one note", listOf(noteId), ids)

        // Search finds a word of what was said in another note, and not the clip.
        runBlocking { import(meetingDocument(MEETING_TITLE)) }
        rule.waitShown(hasContentDescription(MEETING_TITLE, substring = true))
        rule.onNode(hasSetTextAction()).performClick()
        rule.onNode(hasSetTextAction()).performTextInput("star")
        rule.waitGone(row)
        rule.waitShown(hasContentDescription(MEETING_TITLE, substring = true))
        rule.onNode(hasSetTextAction()).performTextReplacement("")
        rule.waitShown(row)

        // Long press: the actions sheet; Move to trash, then Undo. The search field still has the keyboard, so close
        // it first. A sheet opened over the keyboard stands above it and grows when it closes, and on a 16:9 screen
        // Move to trash is below the visible part of the sheet: a tap there falls on the scrim and closes the sheet.
        // Before API 30 the sheet's IME inset can read 0 while the keyboard still closes (API 26, #71), so the inset
        // wait alone let the tap through too early.
        rule.closeKeyboard()
        rule.onNode(row).performTouchInput { longClick() }
        val trash = rule.text(UiR.string.ui_action_move_to_trash)
        rule.waitText(trash)
        rule.waitKeyboardClosed(hasText(trash))
        rule.onNodeWithText(trash).performScrollTo()
        val placement = rule.placementReport(hasText(trash))
        rule.onNodeWithText(trash).performClick()
        rule.waitForTrashSnackbar(noteId, CLIP_TITLE, placement)
        rule.onNode(hasText(rule.text(UiR.string.ui_action_undo))).performClick()
        awaitRows("Undo restores the note") { rows -> rows.any { it.id == noteId } }
        rule.waitShown(row)
    }

    /**
     * The user's path: search, then a long press while the search keyboard is still up. The sheet's window takes the
     * focus, so the keyboard closes and the sheet grows to its full height. Once the input method no longer shows the
     * keyboard, the sheet's window has no keyboard inset and Move to trash stays where it is across an idle frame, its
     * tap must trash the note. The API 26 and 29 failures of [importSearchActionsTrashAndUndo] happened on this path
     * (D66 AND-2): if this test fails, the app has a bug.
     */
    @Test fun actionsOpenedWithTheSearchKeyboardStillMoveToTrash() {
        val showIme = Background.shell("settings get secure $SHOW_IME").trim()
        Background.shell("settings put secure $SHOW_IME 1")
        try {
            val noteId = runBlocking { import(meetingDocument(MEETING_TITLE)) }
            val row = hasContentDescription(MEETING_TITLE, substring = true)
            rule.waitShown(row)
            rule.onNode(hasSetTextAction()).performClick()
            rule.onNode(hasSetTextAction()).performTextInput("star")
            rule.waitForKeyboard("The search keyboard did not open") { Flows.keyboardShown() }
            rule.waitShown(row)

            rule.onNode(row).performTouchInput { longClick() }
            val trash = rule.text(UiR.string.ui_action_move_to_trash)
            rule.waitText(trash)
            rule.waitForKeyboard("The actions sheet left the search keyboard open") { !Flows.keyboardShown() }
            rule.waitKeyboardClosed(hasText(trash))
            rule.onNodeWithText(trash).performScrollTo()
            val settled = rule.settledBounds(hasText(trash))
            val placement = rule.placementReport(hasText(trash))
            rule.onNodeWithText(trash).performClick()
            rule.waitForTrashSnackbar(noteId, MEETING_TITLE, "Settled at $settled. $placement")
        } finally {
            val unset = showIme == "null" || showIme.isEmpty()
            Background.shell(
                if (unset) "settings delete secure $SHOW_IME" else "settings put secure $SHOW_IME $showIme",
            )
        }
    }

    /** A keyboard event wait whose failure also names the focused window. */
    private fun AppRule.waitForKeyboard(message: String, condition: () -> Boolean) {
        try {
            awaitUi { condition() }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("$message; ${Flows.focusReport()}", timeout)
        }
    }

    private fun awaitRows(message: String, matches: (List<LibraryRow>) -> Boolean): List<LibraryRow> = runBlocking {
        withTimeoutOrNull(Flows.SCREEN_MS) { Flows.graph.awaitCatalogue().notes.library().first(matches) }
            ?: throw AssertionError(message)
    }

    private fun AppRule.waitForTrashSnackbar(noteId: String, title: String, placement: String) {
        // Move to trash commits before the snackbar appears. Observe the short-lived UI first (D66 AND-2).
        try {
            waitText(text(UiR.string.ui_library_moved_to_trash, title))
        } catch (notShown: AssertionError) {
            logScreen()
            val stillLive = runBlocking { rows().any { it.id == noteId } }
            val outcome = if (stillLive) "The note stayed in the library" else "The note was moved to trash"
            throw AssertionError(
                "$outcome, and the snackbar was missing. $placement. ${Flows.focusReport()}",
                notShown,
            )
        }
        val removed = runBlocking { rows().none { it.id == noteId } }
        assertTrue("The snackbar appears after the note leaves the library", removed)
    }

    /** The bounds of [matcher]'s node once an idle sync no longer moves them. */
    private fun AppRule.settledBounds(matcher: SemanticsMatcher): Rect {
        var bounds = onNode(matcher).fetchSemanticsNode().boundsInWindow
        val window = (onNode(matcher).fetchSemanticsNode().root as ViewRootForTest).view.rootView
        val deadline = SystemClock.uptimeMillis() + Flows.SCREEN_MS
        while (true) {
            val remaining = deadline - SystemClock.uptimeMillis()
            if (remaining <= 0) throw AssertionError("${matcher.description} still moves, last bounds $bounds")
            UiEvents.draw(window, remaining)
            waitForIdle()
            val next = onNode(matcher).fetchSemanticsNode().boundsInWindow
            if (next == bounds) return next
            bounds = next
        }
    }

    private companion object {
        const val CLIP_TITLE = "fleurs-it-short"
        const val MEETING_TITLE = "Meeting"

        /** The soft keyboard opens also on an emulator with a hardware keyboard, as in SheetAndDraftDeviceTest. */
        const val SHOW_IME = "show_ime_with_hard_keyboard"
    }
}
