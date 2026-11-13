package io.codemyriad.cassini.ui.screens

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.codemyriad.cassini.ui.fixtures.BoardFixtures
import io.codemyriad.cassini.ui.format.FIXED_ZONE
import io.codemyriad.cassini.ui.format.ProvideFixedFormat
import io.codemyriad.cassini.ui.note.NoteEvent
import io.codemyriad.cassini.ui.note.NoteScreen
import io.codemyriad.cassini.ui.recording.RecordingEvent
import io.codemyriad.cassini.ui.recording.RecordingScreen
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
 * What the Recording screen and the Transcribing mode send and announce (features.md sections 2, 3.1 and 14):
 * the chevron minimises the recording and system back is left to the navigation; the suggestion card's buttons and
 * header; the progress card's live region and the stepper's state descriptions.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class ModeBehaviourTest {
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

    /**
     * System back stays with the navigation in :app, which pops `RecordingKey` with the predictive pop (ui.md section
     * 9); that pop minimises. The screen registers no back callback that would take it away.
     */
    @Test fun theChevronMinimisesTheRecordingAndBackIsLeftToNavigation() {
        val events = mutableListOf<RecordingEvent>()
        setThemed { RecordingScreen(BoardFixtures.recording, onEvent = { events += it }) }
        rule.runOnIdle { assertEquals(false, back.hasEnabledCallbacks()) }
        rule.onNodeWithContentDescription("Back to all meetings").performClick()
        rule.onNodeWithText("All meetings").performClick()
        rule.onNodeWithContentDescription("Pause recording").performClick()
        rule.onNodeWithText("Stop").performClick()
        rule.runOnIdle {
            assertEquals(
                listOf(
                    RecordingEvent.Minimise,
                    RecordingEvent.OpenLibrary,
                    RecordingEvent.PauseResume,
                    RecordingEvent.Finish,
                ),
                events,
            )
        }
    }

    @Test fun yesAndNotAnswerTheSuggestion() {
        val events = mutableListOf<NoteEvent>()
        setThemed { NoteScreen(BoardFixtures.transcribing, onEvent = { events += it }) }
        rule.onNodeWithContentDescription("Yes, Luca").performClick()
        rule.onNodeWithContentDescription("Not Luca").performClick()
        rule.runOnIdle {
            assertEquals(
                listOf(NoteEvent.AcceptSuggestion("s2", "luca"), NoteEvent.RejectSuggestion("s2", "luca")),
                events,
            )
        }
    }

    @Test fun theSuggestionHeaderOpensTheNameSheet() {
        val events = mutableListOf<NoteEvent>()
        setThemed { NoteScreen(BoardFixtures.transcribing, onEvent = { events += it }) }
        rule.onNode(hasContentDescription("Speaker 2, at 29 minutes 2 seconds") and hasClickAction()).performClick()
        rule.onNode(hasContentDescription("Giulia, at 28 minutes 31 seconds") and hasClickAction()).performClick()
        rule.runOnIdle { assertEquals(listOf(NoteEvent.SpeakerTap("s2"), NoteEvent.SpeakerTap("s1")), events) }
    }

    @Test fun theStatusIsAPoliteLiveRegionWithStepStatesInDetails() {
        setThemed { NoteScreen(BoardFixtures.transcribing, onEvent = {}) }
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            .assertContentDescriptionContains("Transcribing", substring = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "60%"))
        rule.onNodeWithTag("transcribing-details").performClick()
        val states = listOf(
            "Step 1 of 5: Reading" to "done",
            "Step 2 of 5: Preparing" to "done",
            "Step 3 of 5: Words and voices" to "in progress",
            "Step 4 of 5: Matching" to "not started",
            "Step 5 of 5: Saving" to "not started",
        )
        states.forEach { (step, state) ->
            rule.onNodeWithContentDescription(step).performScrollTo()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, state))
        }
    }

    @Test fun stopKeepsProgress() {
        val events = mutableListOf<NoteEvent>()
        setThemed { NoteScreen(BoardFixtures.transcribing, onEvent = { events += it }) }
        rule.onNodeWithTag("transcribing-details").performClick()
        rule.onNodeWithText("Stop and keep progress").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(listOf<NoteEvent>(NoteEvent.Stop), events) }
    }
}
