package io.codemyriad.cassini.ui.note

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import io.codemyriad.cassini.ui.fixtures.BoardFixtures
import io.codemyriad.cassini.ui.format.ProvideFixedFormat
import io.codemyriad.cassini.ui.theme.CassiniTheme
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

/** Exercise the production route: presentation belongs above the lazy transcript, with live progress. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS-w390dp-h844dp-xhdpi")
class TranscribingPresentationTest {
    @get:Rule val rule = createComposeRule()
    private var state by mutableStateOf(BoardFixtures.transcribing)
    private val events = mutableListOf<NoteEvent>()

    private fun show() {
        rule.setContent {
            CassiniTheme {
                ProvideFixedFormat(locale = Locale.ENGLISH) { NoteScreen(state, onEvent = { events += it }) }
            }
        }
    }

    @Test fun detailsStartClosedAndFollowLiveProgressUntilDismissed() {
        show()
        rule.onNodeWithTag("transcribing-details-content").assertDoesNotExist()
        rule.onNodeWithTag("transcribing-details").performClick()
        rule.onNodeWithText("62%").assertIsDisplayed()
        rule.runOnIdle {
            val mode = state.mode as NoteMode.Processing
            state = state.copy(mode = mode.copy(card = mode.card.copy(percent = 98, words = 5_000)))
        }
        rule.onNodeWithText("98%").assertIsDisplayed()
        rule.onNodeWithText("5,000").performScrollTo().assertIsDisplayed()
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
            .performSemanticsAction(SemanticsActions.Dismiss) { it() }
        rule.onNodeWithTag("transcribing-details-content").assertDoesNotExist()
        rule.onNodeWithTag("transcribing-status").assertIsDisplayed()
        rule.runOnIdle { assertEquals(emptyList<NoteEvent>(), events) }
    }

    @Test fun leavingProcessingClosesTheSheetAndResumingDoesNotReopenIt() {
        show()
        rule.onNodeWithTag("transcribing-details").performClick()
        rule.onNodeWithTag("transcribing-details-content").assertExists()
        rule.runOnIdle { state = state.copy(mode = NoteMode.Paused(100, 200, true)) }
        rule.onNodeWithTag("transcribing-details-content").assertDoesNotExist()
        rule.onNodeWithTag("transcribing-status").assertDoesNotExist()
        rule.runOnIdle { state = state.copy(mode = BoardFixtures.transcribing.mode) }
        rule.onNodeWithTag("transcribing-status").assertIsDisplayed()
        rule.onNodeWithTag("transcribing-details-content").assertDoesNotExist()
    }

    @Test fun detailsAndStopResumeRetainTheHeldAwayDraftPosition() {
        val turn = BoardFixtures.transcribing.turns.first().copy(suggestion = null)
        val turns = List(40) { turn.copy(id = "turn-$it", startMs = it * 1_000L) }.toImmutableList()
        state = state.copy(turns = turns)
        show()
        val draft = rule.onNodeWithTag("transcribing-draft")
        draft.performScrollToIndex(10)
        draft.performTouchInput { swipeUp() }
        fun position() = draft.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        val heldAway = position()
        assertTrue(heldAway > 0f)
        rule.onNodeWithTag("transcribing-details").performClick()
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
            .performSemanticsAction(SemanticsActions.Dismiss) { it() }
        assertEquals(heldAway, position(), 0.1f)
        rule.runOnIdle { state = state.copy(mode = NoteMode.Paused(100, 200, true)) }
        assertEquals(heldAway, position(), 0.1f)
        rule.runOnIdle { state = state.copy(mode = NoteMode.Failed(overDocument = false)) }
        assertEquals(heldAway, position(), 0.1f)
        rule.runOnIdle { state = state.copy(mode = BoardFixtures.transcribing.mode) }
        assertEquals(heldAway, position(), 0.1f)
        rule.runOnIdle { state = state.copy(turns = (turns + turn.copy(id = "appended")).toImmutableList()) }
        assertEquals(heldAway, position(), 0.1f)
    }

    @Test fun compactAnswersKeepFullSpokenNamesAndSeparateNativeTargets() {
        show()
        val yes = rule.onNodeWithContentDescription("Yes, Luca").performScrollTo()
        val no = rule.onNodeWithContentDescription("Not Luca")
        val yesBounds = yes.getUnclippedBoundsInRoot()
        val noBounds = no.getUnclippedBoundsInRoot()
        for (bounds in listOf(yesBounds, noBounds)) {
            assertTrue(bounds.right - bounds.left >= 48.dp)
            assertTrue(bounds.bottom - bounds.top >= 48.dp)
        }
        assertTrue(noBounds.right <= yesBounds.left)
        yes.performClick()
        no.performClick()
        rule.runOnIdle {
            assertEquals(
                listOf(NoteEvent.AcceptSuggestion("s2", "luca"), NoteEvent.RejectSuggestion("s2", "luca")),
                events,
            )
        }
    }

    @Test fun stopUsesTheExistingEventAndBothSurfacesReflectCheckpointing() {
        show()
        rule.onNodeWithTag("transcribing-stop").performClick()
        rule.runOnIdle {
            assertEquals(listOf<NoteEvent>(NoteEvent.Stop), events)
            state = state.copy(mode = (state.mode as NoteMode.Processing).copy(stopBusy = true))
        }
        rule.onNodeWithTag("transcribing-stop").assertIsNotEnabled()
        rule.onNodeWithTag("transcribing-details").performClick()
        rule.onNodeWithTag("transcribing-details-stop").performScrollTo().assertIsNotEnabled()
        rule.runOnIdle { assertEquals(listOf<NoteEvent>(NoteEvent.Stop), events) }
    }
}
