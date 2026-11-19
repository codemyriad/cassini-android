package io.codemyriad.cassini.processing

import android.os.PowerManager
import io.codemyriad.cassini.core.asr.TranscriptionMode
import io.codemyriad.cassini.core.audio.RecordingSpace
import io.codemyriad.cassini.core.models.SpeechModel
import io.codemyriad.cassini.engine.job.PrecomputeStopReason
import io.codemyriad.cassini.recording.CaptureFormat
import io.codemyriad.cassini.recording.RecordingPause
import io.codemyriad.cassini.recording.RecordingState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Verifies [RecordingPrecomputeController] policy, resource/pause/background/thermal/preemption yielding,
 * AAC skip, OOM suppression, and [ThermalMonitor] threshold using deterministic event gates (D88: zero sleeps).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingPrecomputeControllerTest {
    private class Harness(
        scope: CoroutineScope,
        initialMode: TranscriptionMode = TranscriptionMode.TRANSCRIBE_WHILE_RECORDING,
        run: suspend (String, () -> Boolean) -> PrecomputeStopReason,
    ) {
        val recording = MutableStateFlow<RecordingState>(RecordingState.Idle)
        val mode = MutableStateFlow(initialMode)
        val visible = MutableStateFlow(true)
        val throttled = MutableStateFlow(false)
        val busy = MutableStateFlow(false)
        val speech = MutableStateFlow<SpeechModel?>(SpeechModel.PARAKEET)
        val controller = RecordingPrecomputeController(
            RecordingPrecomputeController.Parts(
                scope = scope,
                recordingState = recording,
                transcriptionMode = mode,
                visible = visible,
                throttled = throttled,
                foregroundBusy = busy,
                speechModel = speech,
                runPrecompute = run,
            ),
        )
    }

    private fun active(
        noteId: String = "note-1",
        format: CaptureFormat = CaptureFormat.OPUS,
        pause: RecordingPause? = null,
    ) = RecordingState.Active(
        noteId = noteId,
        title = "Meeting",
        defaultTitle = "Meeting",
        format = format,
        sampleRateHz = format.sampleRateHz,
        elapsedMs = 1_000L,
        pause = pause,
        space = RecordingSpace.of(10_000_000_000L, 6_000L, Long.MAX_VALUE),
        warning = null,
    )

    @Test fun thermalThresholdTriggersAtSevereAndAbove() {
        assertFalse(ThermalMonitor.isThrottled(PowerManager.THERMAL_STATUS_NONE))
        assertFalse(ThermalMonitor.isThrottled(PowerManager.THERMAL_STATUS_LIGHT))
        assertFalse(ThermalMonitor.isThrottled(PowerManager.THERMAL_STATUS_MODERATE))
        assertTrue(ThermalMonitor.isThrottled(PowerManager.THERMAL_STATUS_SEVERE))
        assertTrue(ThermalMonitor.isThrottled(PowerManager.THERMAL_STATUS_CRITICAL))
        assertTrue(ThermalMonitor.isThrottled(PowerManager.THERMAL_STATUS_EMERGENCY))
    }

    @Test fun afterStopRecordOnlyAndAacNeverSpawnPrecompute() = runTest(UnconfinedTestDispatcher()) {
        val starts = AtomicInteger()
        val h = Harness(backgroundScope, initialMode = TranscriptionMode.AFTER_STOP) { _, _ ->
            starts.incrementAndGet()
            PrecomputeStopReason.YIELDED
        }

        h.recording.value = active(format = CaptureFormat.OPUS)
        runCurrent()
        assertEquals("AFTER_STOP must not spawn precompute", 0, starts.get())

        h.mode.value = TranscriptionMode.RECORD_ONLY
        runCurrent()
        assertEquals("RECORD_ONLY must not spawn precompute", 0, starts.get())

        h.mode.value = TranscriptionMode.TRANSCRIBE_WHILE_RECORDING
        h.recording.value = active(format = CaptureFormat.M4A)
        runCurrent()
        assertEquals("API 26-28 AAC capture must not spawn precompute", 0, starts.get())

        h.controller.close()
    }

    @Test fun yieldsImmediatelyOnPauseBackgroundThermalAndPreempt() = runTest(UnconfinedTestDispatcher()) {
        val starts = AtomicInteger()
        val cancels = AtomicInteger()
        val activeNow = AtomicInteger()
        val started = Channel<Int>(Channel.UNLIMITED)
        val stopped = Channel<Int>(Channel.UNLIMITED)
        val h = Harness(backgroundScope) { _, _ ->
            val n = starts.incrementAndGet()
            activeNow.incrementAndGet()
            started.trySend(n)
            var wasCancelled = false
            try {
                awaitCancellation()
            } catch (cancelled: CancellationException) {
                wasCancelled = true
                throw cancelled
            } finally {
                activeNow.decrementAndGet()
                if (wasCancelled) stopped.trySend(cancels.incrementAndGet())
            }
        }

        h.recording.value = active()
        assertEquals(1, started.receive())
        assertEquals(1 to 1, starts.get() to activeNow.get())

        h.recording.value = active(pause = RecordingPause.USER)
        assertEquals(1, stopped.receive())
        assertEquals(0 to 1, activeNow.get() to cancels.get())
        h.recording.value = active(pause = null)
        assertEquals(2, started.receive())
        assertEquals(2 to 1, starts.get() to activeNow.get())

        h.visible.value = false
        assertEquals(2, stopped.receive())
        assertEquals(0 to 2, activeNow.get() to cancels.get())
        h.visible.value = true
        assertEquals(3, started.receive())
        assertEquals(3 to 1, starts.get() to activeNow.get())

        h.throttled.value = true
        assertEquals(3, stopped.receive())
        assertEquals(0 to 3, activeNow.get() to cancels.get())
        h.throttled.value = false
        assertEquals(4, started.receive())
        assertEquals(4 to 1, starts.get() to activeNow.get())

        h.busy.value = true
        assertEquals(4, stopped.receive())
        assertEquals(0 to 4, activeNow.get() to cancels.get())
        h.busy.value = false
        assertEquals(5, started.receive())
        assertEquals(5 to 1, starts.get() to activeNow.get())

        h.controller.stopAndJoin()
        assertEquals(5, stopped.receive())
        assertEquals(0 to 5, activeNow.get() to cancels.get())
        h.controller.close()
    }

    @Test fun oomSuppressesRestartForSameRecordingButAllowsNextRecording() = runTest(UnconfinedTestDispatcher()) {
        val starts = AtomicInteger()
        val seenNotes = Channel<String>(Channel.UNLIMITED)
        val h = Harness(backgroundScope) { noteId, _ ->
            starts.incrementAndGet()
            seenNotes.trySend(noteId)
            if (noteId == "note-1") PrecomputeStopReason.OUT_OF_MEMORY else PrecomputeStopReason.YIELDED
        }

        h.recording.value = active(noteId = "note-1")
        assertEquals("note-1", seenNotes.receive())
        runCurrent()
        assertEquals(1, starts.get())

        h.recording.value = active(noteId = "note-1", pause = RecordingPause.USER)
        runCurrent()
        h.recording.value = active(noteId = "note-1", pause = null)
        runCurrent()
        assertEquals(1, starts.get())

        h.recording.value = RecordingState.Idle
        runCurrent()
        h.recording.value = active(noteId = "note-2")
        assertEquals("note-2", seenNotes.receive())
        assertEquals(2, starts.get())

        h.controller.close()
    }
}
