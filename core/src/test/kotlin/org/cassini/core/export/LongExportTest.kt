package org.cassini.core.export

import org.cassini.core.checkpoint.LongRun
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The export writers over a transcript at the length limit of one note: 11 h of continuous speech, about 104,000
 * words (docs/architecture.md section 3.7). Each writer finishes in linear time and gives clocks past 10 h.
 */
class LongExportTest {
    private val run = LongRun()
    private val hours = 11L
    private val words = run.words(hours * LongRun.HOUR_MS)
    private val labels = (1..LongRun.SPEAKERS).associate { "${run.prefix}$it" to "Speaker $it" }
    private val long = ExportTranscript("Conference day", hours * LongRun.HOUR_MS, words, labels)

    @Test fun textSrtAndVttWriteEveryWordOfAnElevenHourTranscript() {
        val started = System.nanoTime()
        val text = TextExport.write(long, "11 Oct 2026", "No words")
        val srt = SrtWriter.write(long)
        val vtt = VttWriter.write(long)
        val seconds = (System.nanoTime() - started) / 1e9
        assertTrue("took $seconds s", seconds < 30)
        listOf(text, srt, vtt).forEach(::assertExportShape)
        val last = words.last().text
        assertTrue(text.trimEnd().endsWith(last))
        assertTrue("SRT clock past 10 h", srt.contains("10:59:"))
        assertTrue("VTT clock past 10 h", vtt.contains("10:59:"))
        assertTrue(text.contains("10:"))
    }
}
