package org.cassini.android

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptExportTest {
    private val words = listOf(
        Word("a", 0, 400, "Ciao"), Word("a", 450, 900, "Marta."),
        Word("b", 1000, 1400, "Ciao"), Word("b", 1500, 2000, "Luca,"), Word("b", 4000, 4500, "allora?"))
    private val names = mapOf("a" to "Luca", "b" to "Marta")

    @Test fun textHasOneParagraphPerTurn() {
        assertEquals("Luca [00:00]\nCiao Marta.\n\nMarta [00:01]\nCiao Luca, allora?\n",
            TranscriptExport.render(TranscriptExport.Format.TEXT, words, names::getValue))
        assertEquals("Ciao Marta.\n\nCiao Luca, allora?\n", TranscriptExport.render(TranscriptExport.Format.TEXT, words, null))
    }

    @Test fun subtitlesSplitAtSpeakersAndPauses() {
        assertEquals("""1
00:00:00,000 --> 00:00:00,900
Luca: Ciao Marta.

2
00:00:01,000 --> 00:00:02,000
Marta: Ciao Luca,

3
00:00:04,000 --> 00:00:04,500
Marta: allora?
""", TranscriptExport.render(TranscriptExport.Format.SRT, words, names::getValue))
    }

    @Test fun webVttNamesVoicesAndEscapesText() {
        val vtt = TranscriptExport.render(TranscriptExport.Format.VTT, listOf(Word("a", 3_723_004, 3_724_000, "<3 & more")), names::getValue)
        assertEquals("WEBVTT\n\n01:02:03.004 --> 01:02:04.000\n<v Luca>&lt;3 &amp; more\n", vtt)
    }

    @Test fun longRunsBecomeSeveralCuesThatNeverOverlap() {
        val run = (0 until 30).map { Word("a", it * 400L, it * 400L + 500, "parola") }
        val cues = TranscriptExport.cues(run)
        assertEquals(true, cues.size > 1)
        cues.zipWithNext().forEach { (a, b) -> assertEquals(true, a.endMs <= b.startMs) }
        cues.forEach { assertEquals(true, it.endMs - it.startMs <= 6000 + 500 && it.text.length <= 84) }
        assertEquals(run.size, cues.sumOf { it.text.split(' ').size })
    }
}
