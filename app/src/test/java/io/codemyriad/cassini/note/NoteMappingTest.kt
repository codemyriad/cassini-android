package io.codemyriad.cassini.note

import io.codemyriad.cassini.core.asr.ProgressAnchor
import io.codemyriad.cassini.core.library.JobState
import io.codemyriad.cassini.engine.job.BatteryLine
import io.codemyriad.cassini.engine.job.JobPhase
import io.codemyriad.cassini.engine.job.ProgressHeadline
import io.codemyriad.cassini.engine.job.RunningProgress
import io.codemyriad.cassini.engine.job.Step
import io.codemyriad.cassini.engine.job.TimeLeft
import io.codemyriad.cassini.processing.NoteJobState
import io.codemyriad.cassini.ui.components.TrackAnchor
import io.codemyriad.cassini.ui.note.NoteMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Document info texts and the progress card that NoteMapping derives (features.md sections 3.1 and 6). */
class NoteMappingTest {
    @Test fun stoppingMakesOnlyTheExistingStopControlBusy() {
        val row = job(JobState.RUNNING)
        val details = documentNote().copy(job = row)
        val phase = NoteJobState(row, ahead = 0, phase = JobPhase.Stopping)
        val card = requireNotNull(NoteMapping.card(JobPhase.Stopping))
        val mode = NoteMapping.mode(details, phase, card) as NoteMode.Processing
        assertEquals(card, mode.card)
        assertEquals(true, mode.stopBusy)
        val restarted = NoteMapping.mode(details, phase.copy(phase = JobPhase.Starting), card) as NoteMode.Processing
        assertEquals(false, restarted.stopBusy)
    }

    @Test fun subtitlePreservesALocalLabelAndDoesNotInventUnknownTime() {
        val note = documentNote().note
        val mode = io.codemyriad.cassini.ui.note.NoteMode.AudioOnly
        val now = io.codemyriad.cassini.library.NOW
        val zone = io.codemyriad.cassini.library.ROME
        val unknown = NoteMapping.subtitle(note.copy(recordedAt = null), mode, now, zone)!!
        assertEquals(io.codemyriad.cassini.ui.format.DayLabel.Unknown, unknown.day)
        val local = NoteMapping.subtitle(note.copy(recordedAt = "2026-10-07T23:59:00"), mode, now, zone)!!
        assertEquals("2026-10-07T23:59:00", local.recordedLabel)
    }

    @Test
    fun documentInfoNamesTheSpeechModel() {
        assertEquals("Parakeet Ultra, INT8", NoteMapping.modelName("moondream/parakeet-ultra INT8"))
        assertEquals("Parakeet Ultra", NoteMapping.modelName("moondream/parakeet-ultra"))
        assertEquals("moondream/parakeet-ultra-2 INT8", NoteMapping.modelName("moondream/parakeet-ultra-2 INT8"))
        // The model of 0.2.0 and earlier development builds is a model like any other (D51).
        assertEquals("nvidia/parakeet-tdt-0.6b-v3 INT8", NoteMapping.modelName("nvidia/parakeet-tdt-0.6b-v3 INT8"))
        assertEquals("a model this app does not know", "whisper-large", NoteMapping.modelName("whisper-large"))
        assertEquals("nvidia/parakeet-tdt-0.6b-v30", NoteMapping.modelName("nvidia/parakeet-tdt-0.6b-v30"))
    }

    @Test
    fun theCardCarriesTheAnchorOfTheTrack() {
        val progress = RunningProgress(
            headline = ProgressHeadline.FINDING_WORDS,
            rangeStartMs = 52_000,
            rangeEndMs = 80_000,
            percent = 20,
            step = Step.WORDS_AND_VOICES,
            matchingSkipped = false,
            timeLeft = TimeLeft.Calculating,
            doneMs = 54_000,
            totalMs = 260_000,
            battery = BatteryLine.Unavailable,
            words = 120,
            lowMemory = false,
            anchor = ProgressAnchor(0.2f, 0.3f, 52_000f, 16_200),
        )
        assertEquals(TrackAnchor(0.2f, 0.3f, 52_000f, 16_200), NoteMapping.card(progress).anchor)
        assertNull(NoteMapping.card(progress.copy(anchor = null)).anchor)
    }
}
