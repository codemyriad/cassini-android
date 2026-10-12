package org.cassini.core.export

import org.junit.Test

class TextExportTest {
    private val subtitle = "8 Oct 2026, 15:10 · 23:50 · 3 speakers"

    @Test fun manySpeakersWithItalianAccents() {
        val words = said("spk_0", 0, "Allora, la consegna slitta a giovedì?") +
            said("spk_1", 3_000, "Sì, perché il camion è già partito.") +
            said("spk_2", 161_500, "D'accordo, però il prezzo resta quello dell'ordine di marzo.") +
            said("spk_0", 170_000, "Perfetto.")
        assertExport(
            """
            |Chiamata con il fornitore
            |8 Oct 2026, 15:10 · 23:50 · 3 speakers
            |
            |[00:00] Paolo
            |Allora, la consegna slitta a giovedì?
            |
            |[00:03] Speaker 2
            |Sì, perché il camion è già partito.
            |
            |[02:41] Élodie
            |D'accordo, però il prezzo resta quello dell'ordine di marzo.
            |
            |[02:50] Paolo
            |Perfetto.
            |
            """.trimMargin(),
            TextExport.write(transcript(words), subtitle, "No speech in this recording."),
        )
    }

    @Test fun oneSpeakerLeavesTheLabelOut() {
        val words = said("spk_1", 1_000, "Prova uno due tre.")
        assertExport(
            "Prova\n8 Oct 2026, 15:10 · 00:04\n\n[00:01]\nProva uno due tre.\n",
            TextExport.write(transcript(words, title = "Prova"), "8 Oct 2026, 15:10 · 00:04", "No speech."),
        )
    }

    @Test fun emptyTranscriptWritesTheEmptyText() {
        assertExport(
            "Prova\n8 Oct 2026, 15:10 · 00:04\n\nNo speech in this recording.\n",
            TextExport.write(
                transcript(emptyList(), title = "Prova"),
                "8 Oct 2026, 15:10 · 00:04",
                "No speech in this recording.",
            ),
        )
    }

    @Test fun clockFromOneHourAndAMissingLabelUsesTheId() {
        val words = said("spk_0", 5_000, "Iniziamo.") + said("spk_9", 3_725_000, "Ultima cosa.")
        assertExport(
            "Riunione\nsub\n\n[0:00:05] Paolo\nIniziamo.\n\n[1:02:05] spk_9\nUltima cosa.\n",
            TextExport.write(transcript(words, durationMs = 3_800_000, title = "Riunione"), "sub", "-"),
        )
    }

    @Test fun overlappingSpeakersKeepCanonicalOrder() {
        // The second turn starts before the first one ends; the text follows the canonical word order.
        val words = said("spk_0", 10_000, "Io direi di sì") + said("spk_1", 10_500, "No aspetta")
        assertExport(
            "T\ns\n\n[00:10] Paolo\nIo direi di sì\n\n[00:10] Speaker 2\nNo aspetta\n",
            TextExport.write(transcript(words, title = "T"), "s", "-"),
        )
    }
}
