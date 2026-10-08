package org.cassini.android

import org.junit.Assert.*
import org.junit.Test

class DiarizationTest {
    private fun word(start: Long, end: Long) = Word("original", start, end, "w")

    @Test fun majorityOverlapWinsAndIdsFollowFirstAppearance() {
        val turns = listOf(SpeakerTurn(0, 1000, 7), SpeakerTurn(1000, 2000, 3))
        val words = Diarization.assign(listOf(word(100, 400), word(900, 1300), word(1100, 1500)), turns)
        assertEquals(listOf("spk_1", "spk_2", "spk_2"), words.map { it.speaker })
    }
    @Test fun aWordInAGapTakesTheNearestTurn() {
        val turns = listOf(SpeakerTurn(0, 1000, 0), SpeakerTurn(2000, 3000, 1))
        assertEquals(listOf("spk_1", "spk_2"), Diarization.assign(listOf(word(1100, 1200), word(1700, 1800)), turns).map { it.speaker })
    }
    @Test fun noValidTurnsLeavesTheWordsAlone() {
        val words = listOf(word(0, 10))
        assertEquals(words, Diarization.assign(words, emptyList()))
        assertEquals(words, Diarization.assign(words, listOf(SpeakerTurn(-1, 10, 0), SpeakerTurn(10, 0, 1), SpeakerTurn(0, 0, 1), SpeakerTurn(0, 20, -1))))
    }
    @Test fun disjointTurnsAreSummedPerSpeaker() {
        val turns = listOf(SpeakerTurn(0, 100, 0), SpeakerTurn(100, 200, 1), SpeakerTurn(200, 400, 0))
        val words = listOf(word(0, 50), word(110, 150), word(50, 300))
        assertEquals(listOf("spk_1", "spk_2", "spk_1"), Diarization.assign(words, turns).map { it.speaker })
    }
    @Test fun overlappingCopiesCannotDoubleCountTheSameSpeaker() {
        val turns = listOf(SpeakerTurn(0, 400, 0), SpeakerTurn(200, 500, 0), SpeakerTurn(1000, 1600, 1), SpeakerTurn(1600, 1700, 1))
        val words = listOf(word(0, 10), word(1010, 1020), word(0, 1700))
        assertEquals(listOf("spk_1", "spk_2", "spk_2"), Diarization.assign(words, turns).map { it.speaker })
    }
    @Test fun equalOverlapsAndGapTiesAreIndependentOfNativeOrder() {
        val turns = listOf(SpeakerTurn(0, 100, 8), SpeakerTurn(200, 300, 1))
        val words = listOf(word(0, 10), word(200, 210), word(50, 250), word(150, 150))
        val result = Diarization.assign(words, turns)
        assertEquals(result, Diarization.assign(words, turns.reversed()))
        assertEquals(listOf("spk_1", "spk_2", "spk_1", "spk_1"), result.map { it.speaker })
    }
    @Test fun wordOrderAndTimingArePreservedIncludingOverlappingTurns() {
        val words = listOf(word(1000, 1200), word(0, 200), word(1200, 1200))
        val result = Diarization.assign(words, listOf(SpeakerTurn(0, 400, 0), SpeakerTurn(900, 1400, 1)), "new_")
        assertEquals(words, result.map { it.copy(speaker = "original") })
        assertEquals(listOf("new_1", "new_2", "new_1"), result.map { it.speaker })
    }
    @Test fun invalidNativeConfigurationIsRejectedBeforeLoadingModels() {
        for (threads in listOf(0, -1)) {
            try { Diarization.turns(PcmAudio(floatArrayOf(0f), 16000), "", threads); fail() } catch (_: IllegalArgumentException) {}
        }
    }
}
