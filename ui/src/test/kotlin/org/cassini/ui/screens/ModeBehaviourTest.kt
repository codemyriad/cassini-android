package org.cassini.ui.screens

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.cassini.ui.fixtures.BoardFixtures
import org.cassini.ui.format.FIXED_ZONE
import org.cassini.ui.format.ProvideFixedFormat
import org.cassini.ui.note.NoteEvent
import org.cassini.ui.note.NoteScreen
import org.cassini.ui.recording.RecordingEvent
import org.cassini.ui.recording.RecordingScreen
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
 * What the Recording screen and the Transcribing mode send and announce (features.md sections 2, 3.1 and 14):
 * system back and the chevron minimise the recording; the suggestion card's buttons and header; the progress card's
 * live region and the stepper's state descriptions.
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

    @Test fun backAndTheChevronMinimiseTheRecording() {
        val events = mutableListOf<RecordingEvent>()
        setThemed { RecordingScreen(BoardFixtures.recording, onEvent = { events += it }) }
        rule.runOnIdle { back.onBackPressed() }
        rule.onNodeWithContentDescription("Minimise to library (recording continues)").performClick()
        rule.onNodeWithText("Library").performClick()
        rule.onNodeWithContentDescription("Pause recording").performClick()
        rule.onNodeWithText("Finish").performClick()
        rule.runOnIdle {
            assertEquals(
                listOf(
                    RecordingEvent.Minimise,
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
        rule.onNodeWithText("Yes, Luca").performClick()
        rule.onNodeWithText("Not Luca").performClick()
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

    @Test fun theProgressCardIsAPoliteLiveRegionWithStepStates() {
        setThemed { NoteScreen(BoardFixtures.transcribing, onEvent = {}) }
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            .assertContentDescriptionEquals("Finding words and speakers, 60%")
        val states = listOf(
            "Step 1 of 5: Reading" to "done",
            "Step 2 of 5: Preparing" to "done",
            "Step 3 of 5: Words and voices" to "in progress",
            "Step 4 of 5: Matching" to "not started",
            "Step 5 of 5: Saving" to "not started",
        )
        states.forEach { (step, state) ->
            rule.onNodeWithContentDescription(step)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, state))
        }
    }

    @Test fun stopKeepsProgress() {
        val events = mutableListOf<NoteEvent>()
        setThemed { NoteScreen(BoardFixtures.transcribing, onEvent = { events += it }) }
        rule.onNodeWithText("Stop and keep progress").performClick()
        rule.runOnIdle { assertEquals(listOf<NoteEvent>(NoteEvent.Stop), events) }
    }
}
