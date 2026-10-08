package org.cassini.android

import org.junit.Assert.*
import org.junit.Test

class VoiceprintSelectionTest {
    private val rate = 16000
    private fun samples(ms: Long) = (ms * rate / 1000).toInt()
    private fun ms(window: PrintWindow) = window.startSample * 1000L / rate to window.endSample * 1000L / rate
    private fun select(turns: List<SpeakerTurn>, words: List<Word>, totalMs: Long = 600_000) =
        Voiceprints.select(turns, words, rate, samples(totalMs))

    @Test fun edgesAreTrimmedAndConsecutiveWordsFormOneRun() {
        val words = listOf(Word("spk_1", 1000, 3000, "a"), Word("spk_1", 3100, 6000, "b"))
        val windows = select(listOf(SpeakerTurn(0, 7000, 0)), words)
        assertEquals(listOf(1200L to 5800L), windows.getValue("spk_1").map(::ms))
    }
    @Test fun anotherSpeakersTurnIsCutOut() {
        val words = listOf(Word("spk_1", 0, 10000, "a"), Word("spk_2", 20000, 30000, "b"))
        val turns = listOf(SpeakerTurn(0, 10000, 0), SpeakerTurn(4000, 5000, 1), SpeakerTurn(20000, 30000, 1))
        assertEquals(listOf(200L to 3800L, 5200L to 9800L), select(turns, words).getValue("spk_1").map(::ms))
        assertEquals(listOf(20200L to 29800L), select(turns, words).getValue("spk_2").map(::ms))
    }
    @Test fun shortSegmentsAndQuietSpeakersAreDropped() {
        val words = listOf(Word("spk_1", 0, 1800, "a"), Word("spk_2", 2000, 8000, "b"), Word("spk_1", 9000, 12000, "c"))
        val turns = listOf(SpeakerTurn(0, 1800, 0), SpeakerTurn(2000, 8000, 1), SpeakerTurn(9000, 12000, 0))
        val windows = select(turns, words)
        // spk_1 keeps only 2.6 s, under the minimum; its 1.4 s first run is too short anyway.
        assertNull(windows["spk_1"])
        assertEquals(listOf(2200L to 7800L), windows.getValue("spk_2").map(::ms))
    }
    @Test fun longRunsAreSplitIntoBoundedWindows() {
        val windows = select(listOf(SpeakerTurn(0, 30000, 0)), listOf(Word("spk_1", 0, 30000, "a"))).getValue("spk_1")
        assertEquals(3, windows.size)
        assertTrue(windows.all { it.endSample - it.startSample <= samples(Voiceprints.MAX_WINDOW_MS) })
        assertEquals(samples(200), windows.first().startSample); assertEquals(samples(29800), windows.last().endSample)
    }
    @Test fun speechIsCappedAndDrawnFromEachThird() {
        // Ten 9 s runs at the start, two short ones in the middle and at the end.
        val runs = (0 until 10).map { 1000L + it * 10000 to 10000L + it * 10000 } + listOf(300_000L to 305_000L, 550_000L to 555_000L)
        val words = runs.flatMapIndexed { _, (s, e) -> listOf(Word("spk_1", s, e, "x"), Word("spk_2", e + 100, e + 200, "y")) }
        val turns = words.map { SpeakerTurn(it.startMs, it.endMs, if (it.speaker == "spk_1") 0 else 1) }
        val windows = select(turns, words).getValue("spk_1").map(::ms)
        assertTrue(windows.sumOf { it.second - it.first } in 45_000L - Voiceprints.MIN_SEGMENT_MS..45_000L)
        assertTrue(windows.any { it.first in 300_000L..305_000L }); assertTrue(windows.any { it.first in 550_000L..555_000L })
        assertEquals(windows.sortedBy { it.first }, windows)
    }
    @Test fun windowsStayInsideTheAudio() {
        val windows = select(listOf(SpeakerTurn(0, 9000, 0)), listOf(Word("spk_1", 0, 9000, "a")), totalMs = 5000).getValue("spk_1")
        assertEquals(samples(5000), windows.single().endSample)
        assertTrue(select(emptyList(), emptyList()).isEmpty())
    }
    @Test fun printsAreNormalisedAndCompared() {
        val a = Voiceprints.normalised(floatArrayOf(3f, 4f))!!
        assertEquals(1f, Voiceprints.cosine(a, a), 1e-6f)
        assertEquals(0f, Voiceprints.cosine(a, Voiceprints.normalised(floatArrayOf(-4f, 3f))!!), 1e-6f)
        assertNull(Voiceprints.normalised(floatArrayOf(0f, 0f)))
        assertNull(Voiceprints.normalised(floatArrayOf(Float.NaN, 1f)))
    }
}
