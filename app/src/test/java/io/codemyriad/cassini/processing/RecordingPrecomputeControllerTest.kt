package io.codemyriad.cassini.processing

import android.os.PowerManager
import io.codemyriad.cassini.core.asr.TranscriptionMode
import io.codemyriad.cassini.core.audio.RecordingSpace
import io.codemyriad.cassini.core.models.ParakeetSuggestion
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
        initialMode: TranscriptionMode.ParseResult = TranscriptionMode.ParseResult(
            TranscriptionMode.TRANSCRIBE_WHILE_RECORDING,
            fellBackToDefault = false,
        ),
        deviceTotalRamBytes: Long = ParakeetSuggestion.MIN_TOTAL_BYTES,
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
                deviceTotalRamBytes = { deviceTotalRamBytes },
                runPrecompute = run,
            ),
        )
    }

    private fun active(
        noteId: String = "note-1",
        format: CaptureFormat = CaptureFormat.OPUS,
        pause: RecordingPause? = null,
        elapsedMs: Long = 1_000L,
    ) = RecordingState.Active(
        noteId = noteId,
        title = "Meeting",
        defaultTitle = "Meeting",
        format = format,
        sampleRateHz = format.sampleRateHz,
        elapsedMs = elapsedMs,
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
        val h = Harness(
            backgroundScope,
            initialMode = TranscriptionMode.ParseResult(TranscriptionMode.AFTER_STOP, fellBackToDefault = false),
        ) { _, _ ->
            starts.incrementAndGet()
            PrecomputeStopReason.YIELDED
        }

        h.recording.value = active(format = CaptureFormat.OPUS)
        runCurrent()
        assertEquals("AFTER_STOP must not spawn precompute", 0, starts.get())

        h.mode.value = TranscriptionMode.ParseResult(TranscriptionMode.RECORD_ONLY, fellBackToDefault = false)
        runCurrent()
        assertEquals("RECORD_ONLY must not spawn precompute", 0, starts.get())

        h.recording.value = active(format = CaptureFormat.M4A)
        h.mode.value = TranscriptionMode.ParseResult(
            TranscriptionMode.TRANSCRIBE_WHILE_RECORDING,
            fellBackToDefault = false,
        )
        runCurrent()
        assertEquals("API 26-28 AAC capture must not spawn precompute", 0, starts.get())

        h.controller.close()
    }

    @Test fun defaultParakeetPrecomputeRequiresSixGigabytesRamUnlessExplicitlyEnabled() =
        runTest(UnconfinedTestDispatcher()) {
            val lowRamStarts = AtomicInteger()
            val lowRam = Harness(
                backgroundScope,
                initialMode = TranscriptionMode.ParseResult(
                    TranscriptionMode.TRANSCRIBE_WHILE_RECORDING,
                    fellBackToDefault = true,
                ),
                deviceTotalRamBytes = 4_000_000_000L,
            ) { _, _ ->
                lowRamStarts.incrementAndGet()
                awaitCancellation()
            }

            lowRam.recording.value = active(format = CaptureFormat.OPUS)
            runCurrent()
            assertEquals("Default Parakeet precompute must not start below 6 GB RAM", 0, lowRamStarts.get())

            // Whistle (INCLUDED) is not blocked by the Parakeet 6 GB gate.
            lowRam.speech.value = SpeechModel.INCLUDED
            runCurrent()
            assertEquals("Default Whistle precompute may start below 6 GB RAM", 1, lowRamStarts.get())

            // Returning to Parakeet with default mode yields again.
            lowRam.speech.value = SpeechModel.PARAKEET
            runCurrent()
            assertEquals(1, lowRamStarts.get())

            // Explicitly stored transcribeWhileRecording overrides the default RAM gate.
            lowRam.mode.value = TranscriptionMode.ParseResult(
                TranscriptionMode.TRANSCRIBE_WHILE_RECORDING,
                fellBackToDefault = false,
            )
            runCurrent()
            assertEquals("Explicit transcribeWhileRecording must override the default RAM gate", 2, lowRamStarts.get())
            lowRam.controller.close()

            // Devices with >= 6 GB RAM run default Parakeet precompute.
            val sixGbStarts = AtomicInteger()
            val sixGb = Harness(
                backgroundScope,
                initialMode = TranscriptionMode.ParseResult(
                    TranscriptionMode.TRANSCRIBE_WHILE_RECORDING,
                    fellBackToDefault = true,
                ),
                deviceTotalRamBytes = ParakeetSuggestion.MIN_TOTAL_BYTES,
            ) { _, _ ->
                sixGbStarts.incrementAndGet()
                awaitCancellation()
            }

            sixGb.recording.value = active(format = CaptureFormat.OPUS)
            runCurrent()
            assertEquals("Default Parakeet precompute must start at >= 6 GB RAM", 1, sixGbStarts.get())
            sixGb.controller.close()
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

        // Subsequent recording ticks, visibility changes, and pause/resume must NOT restart after OOM.
        h.recording.value = active(noteId = "note-1", elapsedMs = 2_000L)
        runCurrent()
        h.recording.value = active(noteId = "note-1", elapsedMs = 3_000L)
        runCurrent()
        h.visible.value = false
        runCurrent()
        h.visible.value = true
        runCurrent()
        h.recording.value = active(noteId = "note-1", pause = RecordingPause.USER, elapsedMs = 4_000L)
        runCurrent()
        h.recording.value = active(noteId = "note-1", pause = null, elapsedMs = 5_000L)
        runCurrent()
        assertEquals(1, starts.get())

        h.recording.value = RecordingState.Idle
        runCurrent()
        h.recording.value = active(noteId = "note-2")
        assertEquals("note-2", seenNotes.receive())
        assertEquals(2, starts.get())

        h.controller.close()
    }

    @Test fun lowMemorySuppressesRestartForSameRecordingButThermalAllowsResume() = runTest(UnconfinedTestDispatcher()) {
        val starts = AtomicInteger()
        val h = Harness(backgroundScope) { noteId, shouldContinue ->
            starts.incrementAndGet()
            if (noteId == "note-lm") {
                PrecomputeStopReason.LOW_MEMORY
            } else {
                try {
                    awaitCancellation()
                } catch (_: CancellationException) {
                    if (!shouldContinue()) PrecomputeStopReason.YIELDED else PrecomputeStopReason.CANCELLED
                }
            }
        }

        h.recording.value = active(noteId = "note-lm")
        runCurrent()
        assertEquals(1, starts.get())
        h.recording.value = active(noteId = "note-lm", elapsedMs = 2_000L)
        runCurrent()
        assertEquals("LOW_MEMORY must suppress restart on same note", 1, starts.get())

        h.recording.value = active(noteId = "note-th")
        runCurrent()
        assertEquals(2, starts.get())
        h.throttled.value = true
        runCurrent()
        h.throttled.value = false
        runCurrent()
        assertEquals("Thermal yield must resume once unthrottled", 3, starts.get())
        h.controller.close()
    }
}
