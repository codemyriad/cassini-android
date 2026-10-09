package org.cassini.core.export

import org.junit.Test

/**
 * A 1 h 03 min call in Italian and English with three speakers: a named one, a placeholder and a named one with an
 * accent. Speaker 2's "Sì, sì." comes after Élodie's turn in canonical order but starts while she speaks, so the
 * subtitles sort it into her cues.
 */
class ExportGoldenTest {
    private val meeting = transcript(
        resource("meeting.words.tsv").lineSequence().filter { it.isNotEmpty() }.map { line ->
            val field = line.split('\t')
            w(field[0], field[1].toLong(), field[2].toLong(), field[3])
        }.toList(),
        durationMs = 3_790_000,
        title = "Chiamata con il fornitore",
    )

    @Test fun text() {
        val subtitle = "8 Oct 2026, 15:10 · 1:03:10 · 3 speakers"
        assertExport(resource("meeting.txt"), TextExport.write(meeting, subtitle, "No speech in this recording."))
    }

    @Test fun srt() {
        assertExport(resource("meeting.srt"), SrtWriter.write(meeting))
    }

    @Test fun vtt() {
        assertExport(resource("meeting.vtt"), VttWriter.write(meeting))
    }
}
