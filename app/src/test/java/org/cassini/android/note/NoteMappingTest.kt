package org.cassini.android.note

import org.cassini.core.asr.ProgressAnchor
import org.cassini.engine.job.BatteryLine
import org.cassini.engine.job.ProgressHeadline
import org.cassini.engine.job.RunningProgress
import org.cassini.engine.job.Step
import org.cassini.engine.job.TimeLeft
import org.cassini.ui.components.TrackAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Document info texts and the progress card that NoteMapping derives (features.md sections 3.1 and 6). */
class NoteMappingTest {
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
