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
import io.codemyriad.cassini.R
import io.codemyriad.cassini.background.Background
import io.codemyriad.cassini.core.library.AudioKind
import io.codemyriad.cassini.core.library.JobState
import io.codemyriad.cassini.flows.Flows.rows
import io.codemyriad.cassini.flows.Flows.text
import io.codemyriad.cassini.flows.Flows.waitFor
import io.codemyriad.cassini.flows.Flows.waitGone
import io.codemyriad.cassini.flows.Flows.waitShown
import io.codemyriad.cassini.flows.Flows.waitText
import io.codemyriad.cassini.recording.RecordingNotifications
import io.codemyriad.cassini.recording.RecordingOutcome
import io.codemyriad.cassini.recording.RecordingState
import io.codemyriad.cassini.recording.inProgress
import io.codemyriad.cassini.test.NeedsMicrophone
import kotlinx.coroutines.runBlocking
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
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val recording get() = Flows.graph.recording

    @Before fun setUp() = runBlocking {
        Background.grant(Manifest.permission.RECORD_AUDIO)
        Flows.graph.ready.await()
        Background.reset()
    }

    @After fun tearDown() = runBlocking {
        if (recording.state.value.inProgress) recording.finish()
        Background.waitFor("The recording ends", FINISH_MS) { recording.state.value.takeUnless { it.inProgress } }
        recording.dismissEnded()
        Background.reset()
    }

    @Test fun recordMinimiseAndFinishFromTheNotification() {
        rule.waitShown(hasContentDescription(rule.text(UiR.string.ui_library_record_description)))
        rule.onNodeWithContentDescription(rule.text(UiR.string.ui_library_record_description)).performClick()
        rule.waitFor("The recording starts", START_MS) { recording.state.value is RecordingState.Active }
        val finishButton = rule.text(UiR.string.ui_recording_finish)
        rule.waitText(finishButton)
        rule.waitFor("Five seconds are captured", START_MS + CAPTURE_MS) {
            ((recording.state.value as? RecordingState.Active)?.elapsedMs ?: 0) >= CAPTURE_MS
        }
        val active = recording.state.value as RecordingState.Active

        Espresso.pressBack()
        rule.waitGone(hasText(finishButton))
        rule.waitText(rule.text(UiR.string.ui_library_recording_tap))
        assertTrue("Back minimises; the capture goes on", recording.state.value is RecordingState.Active)

        val finish = ContextCompat.getContextForLanguage(rule.activity).getString(R.string.notif_action_finish)
        val manager = rule.activity.getSystemService(NotificationManager::class.java)
        var action: android.app.Notification.Action? = null
        rule.waitFor("The recording notification has a Finish action") {
            action = manager.activeNotifications.firstOrNull { it.id == RecordingNotifications.RECORDING_ID }
                ?.notification?.actions.orEmpty().firstOrNull { it.title?.toString() == finish }
            action != null
        }
        action!!.actionIntent.send()

        rule.waitFor("The recording is saved", FINISH_MS) { recording.state.value is RecordingState.Ended }
        val ended = recording.state.value as RecordingState.Ended
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

    private companion object {
        const val START_MS = 20_000L
        const val CAPTURE_MS = 5_000L
        const val FINISH_MS = 30_000L
    }
}
