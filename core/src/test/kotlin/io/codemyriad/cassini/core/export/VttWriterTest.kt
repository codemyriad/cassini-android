package io.codemyriad.cassini.core.export

import org.junit.Test

class VttWriterTest {
    @Test fun oneSpeakerHasNoVoiceSpan() {
        val words = said("spk_0", 1_000, "Test one two three.")
        val vtt = VttWriter.write(transcript(words))
        assertExport("WEBVTT\n\n00:00:01.000 --> 00:00:02.500\nTest one two three.\n", vtt)
    }

    @Test fun manySpeakersWrapEveryCue() {
        val words = said("spk_0", 0, "So, the delivery slips?") +
            said("spk_1", 1_800, "Yes, on Thursday.") +
            said("spk_1", 4_400, "The truck has already left.")
        assertExport(
            """
            |WEBVTT
            |
            |00:00:00.000 --> 00:00:01.500
            |<v Paolo>So, the delivery slips?</v>
            |
            |00:00:01.800 --> 00:00:02.900
            |<v Speaker 2>Yes, on Thursday.</v>
            |
            |00:00:04.400 --> 00:00:06.300
            |<v Speaker 2>The truck has already left.</v>
            |
            """.trimMargin(),
            VttWriter.write(transcript(words)),
        )
    }

    @Test fun twoLinesStayInsideOneSpan() {
        val words = said("spk_0", 0, "We agreed that the date is right, but before closing", stepMs = 300) +
            said("spk_1", 4_000, "Ok")
        assertExport(
            "WEBVTT\n\n00:00:00.000 --> 00:00:02.900\n<v Paolo>We agreed that the date is\nright, but before closing</v>\n\n" +
                "00:00:04.000 --> 00:00:05.000\n<v Speaker 2>Ok</v>\n",
            VttWriter.write(transcript(words)),
        )
    }

    @Test fun textAndLabelsAreEscaped() {
        val words = listOf(
            w("a", 0, 400, "x<y"),
            w("a", 400, 800, "&"),
            w("a", 800, 1200, "z>w"),
            w("b", 2000, 2400, "ok"),
        )
        val labels = mapOf("a" to "Anna --> <b>&\u0007\n", "b" to "Bruno")
        assertExport(
            "WEBVTT\n\n00:00:00.000 --> 00:00:01.200\n<v Anna --&gt; &lt;b&gt;&amp;>x&lt;y &amp; z&gt;w</v>\n\n" +
                "00:00:02.000 --> 00:00:03.000\n<v Bruno>ok</v>\n",
            VttWriter.write(transcript(words, labels)),
        )
    }

    @Test fun emptyTranscriptIsTheHeaderOnly() {
        assertExport("WEBVTT\n", VttWriter.write(transcript(emptyList())))
    }
}
