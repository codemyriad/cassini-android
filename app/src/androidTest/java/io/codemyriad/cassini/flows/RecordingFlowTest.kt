package io.codemyriad.cassini.flows

import android.Manifest
import android.app.NotificationManager
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.core.content.ContextCompat
import androidx.test.espresso.Espresso
import io.codemyriad.cassini.MainActivity
import io.codemyriad.cassini.background.Background
import io.codemyriad.cassini.core.library.AudioKind
import io.codemyriad.cassini.core.library.JobState
import io.codemyriad.cassini.flows.Flows.rows
import io.codemyriad.cassini.flows.Flows.text
import io.codemyriad.cassini.flows.Flows.waitGone
import io.codemyriad.cassini.flows.Flows.waitShown
import io.codemyriad.cassini.flows.Flows.waitText
import io.codemyriad.cassini.recording.RecordingNotifications
import io.codemyriad.cassini.recording.RecordingOutcome
import io.codemyriad.cassini.recording.RecordingState
import io.codemyriad.cassini.recording.inProgress
import io.codemyriad.cassini.test.NeedsMicrophone
import io.codemyriad.cassini.testing.NotificationWatch
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
 * Recording through the UI with the real microphone (features.md section 2): Record, 5 s of capture, back minimises
 * to the Library with the recording card, Finish from the notification, and the note appears in the Library as a
 * plain note (Audio only) or in the queue. On an emulator the audio may be silence.
 */
@NeedsMicrophone
class RecordingFlowTest {
    @get:Rule val notifications = NotificationWatch()

    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val recording get() = Flows.graph.recording

    @Before fun setUp() = runBlocking {
        Background.grant(Manifest.permission.RECORD_AUDIO)
        Flows.graph.ready.await()
        Background.reset()
    }

    @After fun tearDown() = runBlocking {
        if (recording.state.value.inProgress) recording.finish()
        awaitRecording("The recording ends", FINISH_MS) { !it.inProgress }
        recording.dismissEnded()
        Background.reset()
    }

    @Test fun recordMinimiseAndFinishFromTheNotification() {
        rule.waitShown(hasContentDescription(rule.text(UiR.string.ui_library_record_description)))
        rule.onNodeWithContentDescription(rule.text(UiR.string.ui_library_record_description)).performClick()
        val finishButton = rule.text(UiR.string.ui_recording_finish)
        rule.waitText(finishButton)
        val active = runBlocking {
            val state = awaitRecording("Five seconds are captured", CAPTURE_WAIT_MS) {
                it is RecordingState.Ended || (it is RecordingState.Active && it.elapsedMs >= CAPTURE_MS)
            }
            state as? RecordingState.Active ?: throw AssertionError("Recording ended before five seconds: $state")
        }

        Espresso.pressBack()
        rule.waitGone(hasText(finishButton))
        rule.waitText(rule.text(UiR.string.ui_library_recording_tap))
        assertTrue("Back minimises; the capture goes on", recording.state.value is RecordingState.Active)

        val finish = ContextCompat.getContextForLanguage(rule.activity).getString(UiR.string.ui_recording_finish)
        val manager = rule.activity.getSystemService(NotificationManager::class.java)
        val action = runBlocking {
            notifications.await("The recording notification has a Finish action", Flows.SCREEN_MS) {
                manager.activeNotifications.firstOrNull { it.id == RecordingNotifications.RECORDING_ID }
                    ?.notification?.actions.orEmpty().firstOrNull { it.title?.toString() == finish }
            }
        }
        action.actionIntent.send()

        val ended = runBlocking {
            awaitRecording("The recording ends after Finish", FINISH_MS) { it is RecordingState.Ended }
        } as RecordingState.Ended
        assertTrue("Finish saves the recording: ${ended.outcome}", ended.outcome is RecordingOutcome.Saved)
        assertEquals(active.noteId, ended.noteId)
        rule.waitGone(hasText(rule.text(UiR.string.ui_library_recording_tap)))
        val row = hasContentDescription(active.title, substring = true)
        rule.waitShown(row)
        val saved = runBlocking { rows().single { it.id == active.noteId } }
        assertEquals(AudioKind.PLAIN, saved.audioKind)
        val queued = setOf(null, JobState.QUEUED, JobState.RUNNING, JobState.NEEDS_CONSENT)
        assertTrue("Audio only or in the queue: ${saved.jobState}", saved.jobState in queued)
    }

    private suspend fun awaitRecording(
        message: String,
        timeoutMs: Long,
        matches: (RecordingState) -> Boolean,
    ): RecordingState = withTimeoutOrNull(timeoutMs) { recording.state.first(matches) }
        ?: throw AssertionError("$message: ${recording.state.value}")

    private companion object {
        const val CAPTURE_MS = 5_000L
        const val CAPTURE_WAIT_MS = 30_000L
        const val FINISH_MS = 30_000L
    }
}
