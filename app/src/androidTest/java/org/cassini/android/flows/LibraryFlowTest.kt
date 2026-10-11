package org.cassini.android.flows

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
import kotlinx.coroutines.runBlocking
import org.cassini.android.MainActivity
import org.cassini.android.background.Background
import org.cassini.android.flows.Flows.import
import org.cassini.android.flows.Flows.meetingDocument
import org.cassini.android.flows.Flows.pressBackWhenFocused
import org.cassini.android.flows.Flows.row
import org.cassini.android.flows.Flows.rows
import org.cassini.android.flows.Flows.text
import org.cassini.android.flows.Flows.view
import org.cassini.android.flows.Flows.waitFor
import org.cassini.android.flows.Flows.waitGone
import org.cassini.android.flows.Flows.waitKeyboardClosed
import org.cassini.android.flows.Flows.waitShown
import org.cassini.android.flows.Flows.waitText
import org.cassini.core.library.AudioKind
import org.cassini.core.library.JobState
import org.cassini.test.CiSmoke
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.cassini.ui.R as UiR

/**
 * The Library on the device (features.md section 1): the empty state; the FLEURS clip opened through ACTION_VIEW
 * from a `file://` copy in the scratch cache opens its note with the Library under it, and a second ACTION_VIEW of
 * the same file opens the same note; the row is Audio only (or Queued); search finds a spoken word of another note;
 * a long press opens the actions sheet, which closes the search keyboard; Move to trash shows the snackbar and Undo
 * restores the note.
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
        rule.waitFor("The import makes one note") { rows().size == 1 }
        val noteId = runBlocking { rows().single().id }
        rule.waitShown(hasContentDescription(rule.text(UiR.string.ui_back_to_library)))
        rule.waitText(CLIP_TITLE)
        rule.pressBackWhenFocused()
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
        rule.pressBackWhenFocused()
        rule.waitShown(row)
        val ids = runBlocking { rows().map { it.id } }
        assertEquals("A second ACTION_VIEW of the same file keeps one note", listOf(noteId), ids)

        // Search finds a word of what was said in another note, and not the clip.
        runBlocking { import(meetingDocument(MEETING_TITLE)) }
        rule.waitShown(hasContentDescription(MEETING_TITLE, substring = true))
        rule.onNode(hasSetTextAction()).performClick()
        rule.onNode(hasSetTextAction()).performTextInput("cominciam")
        rule.waitGone(row)
        rule.waitShown(hasContentDescription(MEETING_TITLE, substring = true))
        rule.onNode(hasSetTextAction()).performTextReplacement("")
        rule.waitShown(row)

        // Long press: the actions sheet; Move to trash, then Undo.
        rule.onNode(row).performTouchInput { longClick() }
        val trash = rule.text(UiR.string.ui_action_move_to_trash)
        rule.waitText(trash)
        // The search field still has the keyboard. Until the keyboard closes, the sheet stands above it, and on a 16:9
        // screen Move to trash is below the visible part of the sheet: a tap there falls on the scrim and closes the
        // sheet. A slow emulator keeps the keyboard open for more than a second after the sheet opens.
        rule.waitKeyboardClosed(hasText(trash))
        rule.onNodeWithText(trash).performScrollTo().performClick()
        val moved = rule.text(UiR.string.ui_library_moved_to_trash, CLIP_TITLE)
        rule.waitText(moved)
        rule.waitFor("The note is in the trash") { row(CLIP_TITLE) == null }
        rule.onNode(hasText(rule.text(UiR.string.ui_action_undo))).performClick()
        rule.waitFor("Undo restores the note") { row(CLIP_TITLE)?.id == noteId }
        rule.waitShown(row)
    }

    private companion object {
        const val CLIP_TITLE = "fleurs-it-short"
        const val MEETING_TITLE = "Riunione"
    }
}
