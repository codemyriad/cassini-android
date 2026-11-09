package io.codemyriad.cassini.core.export

import io.codemyriad.cassini.core.format.Transcript
import org.junit.Assert.assertEquals
import org.junit.Test

class TextExportTest {
    private val subtitle = "8 Oct 2026, 15:10 · 23:50 · 3 speakers"

    @Test fun manySpeakersWithAccentedNames() {
        val words = said("spk_0", 0, "So, is the delivery late?") +
            said("spk_1", 3_000, "Yes, because the truck has already left.") +
            said("spk_2", 161_500, "Agreed, but the price stays as in the March order.") +
            said("spk_0", 170_000, "Perfect.")
        assertExport(
            """
            |Call with supplier
            |8 Oct 2026, 15:10 · 23:50 · 3 speakers
            |
            |[00:00] Paolo
            |So, is the delivery late?
            |
            |[00:03] Speaker 2
            |Yes, because the truck has already left.
            |
            |[02:41] Élodie
            |Agreed, but the price stays as in the March order.
            |
            |[02:50] Paolo
            |Perfect.
            |
            """.trimMargin(),
            TextExport.write(transcript(words), subtitle, "No speech in this recording."),
        )
    }

    @Test fun oneSpeakerShowsTheInitialNameAsInTheReader() {
        val words = said("spk_1", 1_000, "Test one two three.")
        assertExport(
            "Test\n8 Oct 2026, 15:10 · 00:04\n\n[00:01] Speaker 2\nTest one two three.\n",
            TextExport.write(transcript(words, title = "Test"), "8 Oct 2026, 15:10 · 00:04", "No speech."),
        )
    }

    @Test fun emptyTranscriptWritesTheEmptyText() {
        assertExport(
            "Test\n8 Oct 2026, 15:10 · 00:04\n\nNo speech in this recording.\n",
            TextExport.write(
                transcript(emptyList(), title = "Test"),
                "8 Oct 2026, 15:10 · 00:04",
                "No speech in this recording.",
            ),
        )
    }

    @Test fun clockFromOneHourAndAMissingLabelUsesTheId() {
        val words = said("spk_0", 5_000, "Starting.") + said("spk_9", 3_725_000, "Last thing.")
        assertExport(
            "Meeting\nsub\n\n[0:00:05] Paolo\nStarting.\n\n[1:02:05] spk_9\nLast thing.\n",
            TextExport.write(transcript(words, durationMs = 3_800_000, title = "Meeting"), "sub", "-"),
        )
    }

    @Test fun overlappingSpeakersKeepCanonicalOrder() {
        // The second turn starts before the first one ends; the text follows the canonical word order.
        val words = said("spk_0", 10_000, "I would say yes") + said("spk_1", 10_500, "No wait")
        assertExport(
            "T\ns\n\n[00:10] Paolo (over Speaker 2)\nI would say yes\n\n[00:10] Speaker 2 (over Paolo)\nNo wait\n",
            TextExport.write(transcript(words, title = "T"), "s", "-"),
        )
    }

    @Test fun acknowledgmentIsOneParagraphWithAnInlineChipAndNoOver() {
        val words = Transcript.fromJson(ReadingTurnsTest.resource("acknowledgment.words.json")).words
        assertEquals(
            "T\ns\n\n[00:00] Ada\nThe release candidate is ready for testing, " +
                "(Ben: Sounds good.) and I will send the download link after this call.\n",
            TextExport.write(ExportTranscript("T", 8500, words, mapOf("ada" to "Ada", "ben" to "Ben")), "s", "-"),
        )
    }

    @Test fun competingSentencesStayWholeAndNameTheOtherSpeaker() {
        val words = Transcript.fromJson(ReadingTurnsTest.resource("competing.words.json")).words
        assertEquals(
            "T\ns\n\n[00:00] Ada (over Ben)\nI can check the Linux build and report any problems today.\n\n" +
                "[00:00] Ben (over Ada)\nI will test the browser connection and check the microphone settings.\n",
            TextExport.write(ExportTranscript("T", 5550, words, mapOf("ada" to "Ada", "ben" to "Ben")), "s", "-"),
        )
    }
}
