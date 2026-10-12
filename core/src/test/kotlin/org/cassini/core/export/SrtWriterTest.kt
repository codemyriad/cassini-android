package org.cassini.core.export

import org.junit.Test

class SrtWriterTest {
    @Test fun oneSpeakerHasNoPrefix() {
        val words = said("spk_0", 1_000, "Prova uno due tre.")
        assertExport(
            "1\n00:00:01,000 --> 00:00:02,500\nProva uno due tre.\n",
            SrtWriter.write(transcript(words)),
        )
    }

    @Test fun manySpeakersArePrefixedOnChangeOnly() {
        val words = said("spk_0", 0, "Allora, la consegna slitta?") +
            said("spk_1", 1_800, "Sì, a giovedì.") +
            said("spk_1", 4_400, "Il camion è già partito.") +
            said("spk_2", 6_500, "Però il prezzo resta.")
        assertExport(
            """
            |1
            |00:00:00,000 --> 00:00:01,500
            |Paolo: Allora, la consegna slitta?
            |
            |2
            |00:00:01,800 --> 00:00:02,900
            |Speaker 2: Sì, a giovedì.
            |
            |3
            |00:00:04,400 --> 00:00:06,300
            |Il camion è già partito.
            |
            |4
            |00:00:06,500 --> 00:00:08,000
            |Élodie: Però il prezzo resta.
            |
            """.trimMargin(),
            SrtWriter.write(transcript(words)),
        )
    }

    @Test fun emptyTranscriptIsOneNewline() {
        assertExport("\n", SrtWriter.write(transcript(emptyList())))
    }

    @Test fun textIsNotEscaped() {
        val words = listOf(w("spk_0", 0, 1500, "x<y"), w("spk_0", 1500, 1800, "&"))
        assertExport("1\n00:00:00,000 --> 00:00:01,800\nx<y &\n", SrtWriter.write(transcript(words)))
    }

    @Test fun clockBeyondOneHour() {
        val words = said("spk_0", 3_725_250, "Ultima cosa")
        val srt = SrtWriter.write(transcript(words, durationMs = 3_800_000))
        assertExport("1\n01:02:05,250 --> 01:02:06,250\nUltima cosa\n", srt)
    }
}
