package io.codemyriad.cassini.background

import android.Manifest
import android.app.NotificationManager
import android.util.Log
import androidx.core.content.ContextCompat
import io.codemyriad.cassini.R
import io.codemyriad.cassini.background.Background.graph
import io.codemyriad.cassini.background.Background.waitFor
import io.codemyriad.cassini.core.library.AudioKind
import io.codemyriad.cassini.core.library.JobState
import io.codemyriad.cassini.recording.AfterFinish
import io.codemyriad.cassini.recording.RecordingNotifications
import io.codemyriad.cassini.recording.RecordingOutcome
import io.codemyriad.cassini.recording.RecordingState
import io.codemyriad.cassini.recording.inProgress
import io.codemyriad.cassini.test.NeedsMicrophone
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The new `RecordingService` with the real microphone (docs/architecture.md section 5.8): start, Home, Finish from
 * the notification action. On an emulator the audio may be silence, so the test checks the files and the states.
 */
@NeedsMicrophone
class RecordingServiceTest {
    private val watch = MainLooperWatch()

    @Before
    fun setUp() = runBlocking {
        Background.grant(Manifest.permission.RECORD_AUDIO)
        graph.ready.await()
        Background.reset()
    }

    @After
    fun tearDown() = runBlocking {
        watch.stop()
        val recording = graph.recording
        if (recording.state.value.inProgress) recording.finish()
        awaitRecording("The recording ends", FINISH_MS) { !it.inProgress }
        recording.dismissEnded()
        Background.reset()
    }

    @Test
    fun aRecordingFinishedFromTheNotificationAfterHomeIsSavedAndHandedOff() = runBlocking {
        val recording = graph.recording
        watch.start()
        assertTrue("A recording starts", recording.start("Background check"))
        val active = awaitRecording("The recording is active", START_MS) {
            it is RecordingState.Active
        } as RecordingState.Active
        Background.shell("input keyevent KEYCODE_HOME")
        awaitRecording("Audio is captured", START_MS) {
            it is RecordingState.Active && it.elapsedMs >= CAPTURE_MS
        }

        val finish = ContextCompat.getContextForLanguage(Background.context).getString(R.string.notif_action_finish)
        val manager = Background.context.getSystemService(NotificationManager::class.java)
        val notification = waitFor("The recording notification is posted") {
            manager.activeNotifications.firstOrNull { it.id == RecordingNotifications.RECORDING_ID }?.notification
        }
        val action = notification.actions.orEmpty().firstOrNull { it.title?.toString() == finish }
        assertNotNull("The notification has a Finish action", action)
        action!!.actionIntent.send()

        val ended = awaitRecording("The recording ends", FINISH_MS) {
            it is RecordingState.Ended
        } as RecordingState.Ended
        val longest = checkNotNull(watch.stop()) { "The main-looper watch observed no complete dispatches" }
        val outcome = ended.outcome
        assertTrue("Finish saves the recording: $outcome", outcome is RecordingOutcome.Saved)
        assertEquals(active.noteId, ended.noteId)
        val row = graph.awaitCatalogue().notes.rowBlocking(active.noteId)
        assertNotNull(row)
        assertEquals(AudioKind.PLAIN, row!!.audioKind)
        assertTrue("The note has a duration", row.durationMs > 0)
        val capture = File(graph.paths.note(active.noteId), row.audioFile)
        assertTrue("The capture file has audio: $capture", capture.length() > 0)
        val job = Background.row(active.noteId)?.jobState
        val next = (outcome as RecordingOutcome.Saved).next
        Log.i(TAG, "Saved ${row.durationMs} ms, ${capture.length()} bytes, next $next, job $job")
        if (next == AfterFinish.TRANSCRIBE) {
            assertTrue("A note with consent is queued: $job", job == JobState.QUEUED || job == JobState.RUNNING)
        } else {
            assertEquals("A note without consent stays plain", null, job)
        }
        val sample = "${longest.cpuMs} ms CPU (${longest.message})"
        Log.i(TAG, "Most CPU in one main-thread dispatch: $sample")
        assertTrue("A main-thread dispatch exceeded $MAX_MAIN_CPU_MS ms CPU: $sample", longest.cpuMs <= MAX_MAIN_CPU_MS)
    }

    private suspend fun awaitRecording(
        message: String,
        timeoutMs: Long,
        matches: (RecordingState) -> Boolean,
    ): RecordingState = withTimeoutOrNull(timeoutMs) { graph.recording.state.first(matches) }
        ?: throw AssertionError("$message (waited ${timeoutMs / 1_000} s)")

    private companion object {
        const val TAG = "RecordingServiceTest"
        const val START_MS = 20_000L
        const val CAPTURE_MS = 3_000L
        const val FINISH_MS = 30_000L
        const val MAX_MAIN_CPU_MS = 100L
    }
}
