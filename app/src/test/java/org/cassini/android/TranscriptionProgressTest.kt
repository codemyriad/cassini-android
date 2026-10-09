package org.cassini.android

import org.junit.Assert.*
import org.junit.Test

class TranscriptionProgressTest {
    @Test fun stagesDoNotAdvanceOrResetCompletedAudio() {
        val state = TranscriptionProgress(100000)
        state.complete(28000, 100000, 40, 10000)
        for (stage in TranscriptionProgress.Stage.entries) {
            state.stage(stage, 11000, 26000, 54000)
            val sample = state.snapshot(12000)
            assertEquals(28, sample.percent)
            assertEquals(28000L, sample.doneMs)
            assertEquals(40, sample.words)
        }
    }
    @Test fun briefReadingStagesDoNotFlashBetweenNativeCalls() {
        val state = TranscriptionProgress(100000)
        state.stage(TranscriptionProgress.Stage.WORDS, 1000, 0, 28000)
        assertEquals(TranscriptionProgress.Stage.WORDS, state.snapshot(1500).stage)
        state.stage(TranscriptionProgress.Stage.READING, 1600, end = 28000)
        state.stage(TranscriptionProgress.Stage.SPEAKERS, 1650, 0, 28000)
        assertEquals(TranscriptionProgress.Stage.WORDS, state.snapshot(1700).stage)
        assertEquals(TranscriptionProgress.Stage.SPEAKERS, state.snapshot(2100).stage)
    }
    @Test fun paceWaitsForWholeChunksAndIgnoresColdModelLoading() {
        val state = TranscriptionProgress(200000)
        state.complete(28000, 200000, 20, 100000)
        assertNull(state.snapshot(100000).remainingMs)
        state.complete(54000, 200000, 40, 113000)
        assertNull(state.snapshot(113000).remainingMs)
        state.complete(80000, 200000, 60, 126000)
        assertEquals(60000L, state.snapshot(126000).remainingMs)
        state.stage(TranscriptionProgress.Stage.WORDS, 127000, 78000, 106000)
        assertEquals(58000L, state.snapshot(128000).remainingMs)
        state.complete(106000, 200000, 80, 152000)
        assertEquals(56400L, state.snapshot(152000).remainingMs)
    }
    @Test fun unknownDurationsRemainUnknownUntilMeasuredAndFinalizationIsExplicit() {
        val state = TranscriptionProgress(0)
        assertNull(state.snapshot(1000).percent)
        state.total(54000)
        assertEquals(0, state.snapshot(1000).percent)
        state.complete(54000, 54000, 50, 10000)
        state.stage(TranscriptionProgress.Stage.SAVING, 10000)
        assertEquals(100, state.snapshot(11000).percent)
        assertEquals(0L, state.snapshot(11000).remainingMs)
        assertEquals(TranscriptionProgress.Stage.SAVING, state.snapshot(11000).stage)
    }
    @Test fun estimatesAreRoundedWithoutPretendingToBePreciseToTheSecond() {
        assertEquals(360000L, TranscriptionProgress.roundedRemaining(330001))
        assertEquals(90000L, TranscriptionProgress.roundedRemaining(81000))
        assertEquals(25000L, TranscriptionProgress.roundedRemaining(21000))
        assertEquals(0L, TranscriptionProgress.roundedRemaining(0))
    }
    @Test fun estimateRangesWidenWhenProcessingSlowsAndResumeIgnoresReloadTime() {
        val state = TranscriptionProgress(400000)
        state.complete(28000, 400000, 30, 50000)
        state.complete(54000, 400000, 60, 63000)
        state.complete(80000, 400000, 90, 76000)
        val steady = state.snapshot(76000)
        assertTrue(steady.remainingLowMs!! < steady.remainingMs!!)
        assertTrue(steady.remainingHighMs!! > steady.remainingMs!!)
        state.complete(106000, 400000, 120, 128000)
        val slow = state.snapshot(128000)
        assertTrue(slow.remainingHighMs!! - slow.remainingLowMs!! > steady.remainingHighMs!! - steady.remainingLowMs!!)
        val resumed = TranscriptionProgress(400000)
        resumed.restore(106000, 400000, 120, 128000)
        resumed.complete(132000, 400000, 150, 228000)
        resumed.complete(158000, 400000, 180, 241000)
        resumed.complete(184000, 400000, 210, 254000)
        assertEquals(108000L, resumed.snapshot(254000).remainingMs)
    }

}
