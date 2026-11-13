package io.codemyriad.cassini.note

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
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
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import io.codemyriad.cassini.core.format.Word
import io.codemyriad.cassini.core.library.AudioKind
import io.codemyriad.cassini.core.library.JobState
import io.codemyriad.cassini.data.checkpoint.DraftTranscript
import io.codemyriad.cassini.engine.job.BatteryLine
import io.codemyriad.cassini.engine.job.DraftState
import io.codemyriad.cassini.engine.job.JobPhase
import io.codemyriad.cassini.engine.job.ProgressHeadline
import io.codemyriad.cassini.engine.job.RunningProgress
import io.codemyriad.cassini.engine.job.Step
import io.codemyriad.cassini.engine.job.TimeLeft
import io.codemyriad.cassini.library.CLOCK
import io.codemyriad.cassini.nav.NoteKey
import io.codemyriad.cassini.ui.note.NoteAction
import io.codemyriad.cassini.ui.note.NoteEvent
import io.codemyriad.cassini.ui.note.NoteScreen
import io.codemyriad.cassini.ui.theme.CassiniTheme
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real ViewModel and mapping over coordinator phases, rendered through the production Compose route. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS-w390dp-h844dp-xhdpi")
class TranscribingLifecycleTest {
    @get:Rule val rule = createComposeRule()
    private val store = ViewModelStore()
    private val ports = FakeNotePorts()

    @After fun clear() { store.clear() }

    @Test
    @Suppress("LongMethod") // One production transition path with the sheet open and the draft held away.
    fun stoppingAndResumingPreserveTheLiveSheetAndHeldAwayDraft() {
        val words = (0 until 40).map { Word("speaker-${it % 2}", it * 1_000L, it * 1_000L + 900, "Sentence $it.") }
        val progress = RunningProgress(
            headline = ProgressHeadline.FINDING_WORDS,
            rangeStartMs = 0,
            rangeEndMs = 40_000,
            percent = 62,
            step = Step.WORDS_AND_VOICES,
            matchingSkipped = false,
            timeLeft = TimeLeft.Range(360_000, 540_000),
            doneMs = 40_000,
            totalMs = 80_000,
            battery = BatteryLine.Unavailable,
            words = 40,
            lowMemory = false,
        )
        val draftState = DraftState(words, emptyMap(), emptySet(), emptyList(), Long.MAX_VALUE, true)
        val live = JobPhase.Transcribing(progress, draftState)
        ports.details.value = documentNote(audioKind = AudioKind.PLAIN).let {
            it.copy(note = it.note.copy(durationMs = 80_000))
        }
        fun row(state: JobState, doneMs: Long = 0) = job(state, doneMs = doneMs).copy(totalMs = 80_000)
        ports.setJob(row(JobState.RUNNING), phase = live)
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return NoteViewModel(SavedStateHandle(), NoteKey(NOTE), ports, CLOCK, Dispatchers.Main.immediate) as T
            }
        }
        val vm = ViewModelProvider(store, factory)[NoteViewModel::class.java]
        rule.setContent {
            val state by vm.state.collectAsState()
            CassiniTheme { NoteScreen(state, onEvent = vm::onEvent) }
        }
        val draft = rule.onNodeWithTag("transcribing-draft")
        draft.performScrollToIndex(10)
        draft.performTouchInput { swipeUp(startY = centerY + 50f, endY = centerY - 50f, durationMillis = 500) }
        val axis = draft.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("The reader is held away from both ends", axis.value() > 0f && axis.value() < axis.maxValue())
        fun anchor(): Pair<String, Float> {
            val viewport = draft.fetchSemanticsNode().boundsInRoot
            val first = rule.onAllNodes(hasText("Sentence ", substring = true)).fetchSemanticsNodes()
                .filter { it.boundsInRoot.bottom > viewport.top && it.boundsInRoot.top < viewport.bottom }
                .minBy { it.boundsInRoot.top }
            val text = first.config[SemanticsProperties.Text].first().text
            val offset = rule.onNodeWithText(text).getUnclippedBoundsInRoot().top - draft.getUnclippedBoundsInRoot().top
            return text to offset.value
        }
        val held = anchor()
        assertTrue(held.first != "Sentence 0.")
        fun assertAnchor() {
            val current = anchor()
            assertEquals(held.first, current.first)
            assertEquals(held.second, current.second, 0.5f)
        }
        rule.onNodeWithTag("transcribing-details").performClick()
        rule.onNodeWithText("62%").assertIsDisplayed()
        rule.onNodeWithTag("transcribing-details-stop").performClick()
        rule.runOnIdle { ports.setJob(row(JobState.RUNNING), phase = JobPhase.Stopping(live)) }
        rule.onNodeWithText("62%").assertIsDisplayed()
        rule.onNodeWithText("00:40 of 01:20").assertIsDisplayed()
        rule.onNodeWithText("40").assertIsDisplayed()
        rule.onNodeWithTag("transcribing-details-stop").assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Saving…"))
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
            .performSemanticsAction(SemanticsActions.Dismiss) { it() }
        rule.onNodeWithContentDescription("More actions").performClick()
        rule.onNode(
            hasText("Stop and keep progress").and(hasClickAction())
                .and(SemanticsMatcher.keyNotDefined(SemanticsProperties.TestTag)),
        ).performScrollTo()
            .assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Saving…"))
        rule.runOnIdle {
            vm.onEvent(NoteEvent.Stop)
            vm.onEvent(NoteEvent.SheetAction(NoteAction.STOP))
            assertEquals("checkpointing must not send another stop", 1, ports.calls.count { it == "stop" })
        }
        assertAnchor()
        rule.runOnIdle {
            ports.draft = DraftTranscript(words, emptyMap(), ports.names.value, 40_000, 80_000)
            ports.setJob(row(JobState.PAUSED, doneMs = 40_000))
        }
        assertAnchor()
        rule.runOnIdle { ports.setJob(row(JobState.QUEUED, doneMs = 40_000)) }
        assertAnchor()
        for (phase in listOf(JobPhase.Starting, JobPhase.Downloading(null), JobPhase.WaitingForPrevious, live)) {
            rule.runOnIdle { ports.setJob(row(JobState.RUNNING, doneMs = 40_000), phase = phase) }
            assertAnchor()
            rule.onNodeWithTag("transcribing-details-content").assertDoesNotExist()
        }
        rule.runOnIdle {
            val appended = live.draft.copy(words = words + Word("extra", 80_000, 81_000, "Newest."))
            ports.setJob(row(JobState.RUNNING), phase = live.copy(draft = appended))
        }
        assertAnchor()
    }
}
