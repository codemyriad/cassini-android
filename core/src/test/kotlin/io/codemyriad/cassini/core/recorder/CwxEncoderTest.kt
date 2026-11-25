package io.codemyriad.cassini.core.recorder

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The golden files in `src/test/resources/recorder/` come from the recorder's reference encoder (`build_cwx_bytes`
 * in `tools/cassini_to_device.py` of the voice-recorder repository) and the cases in `cwx-cases.json`. To write them
 * again:
 *
 *     python3 -I core/src/test/resources/recorder/make_cwx_golden.py PATH/TO/tools/cassini_to_device.py
 */
class CwxEncoderTest {
    @Test fun matchesTheReferenceEncoderByteForByte() {
        val cases = JSONObject(resource("cwx-cases.json").toString(Charsets.UTF_8)).getJSONArray("cases")
        assertEquals(3, cases.length())
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val name = case.getString("name")
            assertArrayEquals(name, resource("$name.cwx"), CwxEncoder.encode(transcript(case)))
        }
    }

    @Test fun theRecorderReaderAcceptsTheMixedCase() {
        val reader = CwxReader(CwxEncoder.encode(mixed()))
        // Typographic quotes and dashes become ASCII, the title stops at 31 bytes without splitting a character.
        assertEquals("\"Budget\" review - Q3 été übe", reader.title)
        val labels = reader.speakers.map { it.label }
        assertEquals(listOf("Paola Rossi", "Speaker 2", "Élodie de la Fonta", "Unknown"), labels)
        val colors = reader.speakers.map { it.color }
        assertEquals(listOf(0x3339, CwxEncoder.rgb565(0xFFC346), 0x0000, CwxEncoder.rgb565(0xD2D2D2)), colors)
        assertEquals(7400L, reader.durationMs)
        assertEquals(8L, reader.seekCount)
        val words = reader.wordsFrom(0)
        assertEquals(8, words.size)
        val texts = words.take(5).map { it.text }
        assertEquals(listOf("Hello's", "world...", "a b c", "ЗдравствуйтеЗдравствуйт", "overlap"), texts)
        // End before start becomes start; a negative start becomes 0; the out-of-range speaker is Unknown.
        assertEquals(950L to 950L, words[2].startMs to words[2].endMs)
        assertEquals(0L to 3100L, words[5].startMs to words[5].endMs)
        assertEquals(3, words[5].speaker)
        assertEquals(listOf(1, 0, 2, 1, 1, 1, 3, 1), words.map { it.flags })
        // Turn order is kept: "overlap" starts before the word in front of it.
        assertTrue(words[4].startMs < words[3].startMs)
        assertEquals(46, words[3].text.toByteArray().size)
    }

    @Test fun eachSeekEntryPointsAtTheFirstWordThatEndsAtOrAfterItsSecond() {
        val bytes = CwxEncoder.encode(mixed())
        val reader = CwxReader(bytes)
        val all = reader.wordsFrom(0)
        for (second in 0 until reader.seekCount) {
            val from = reader.wordsFrom(second * 1000)
            val expected = all.indexOfFirst { it.endMs >= second * 1000 }.let { if (it < 0) all.size else it }
            assertEquals("second $second", all.size - expected, from.size)
            from.firstOrNull()?.let { assertEquals(all[expected].offset, it.offset) }
        }
    }

    @Test fun anEmptyTranscriptHasOneSpeakerAndOneSeekEntry() {
        val reader = CwxReader(CwxEncoder.encode(CwxTranscript("", 0, emptyList(), emptyList())))
        assertEquals("Meeting", reader.title)
        assertEquals(listOf("Speaker 1"), reader.speakers.map { it.label })
        assertEquals(1L, reader.seekCount)
        assertEquals(0L, reader.wordCount)
    }

    @Test fun theDurationGrowsToTheLastWord() {
        val transcript = CwxTranscript("t", 1000, listOf(CwxSpeaker("A")), listOf(CwxWord(0, 2500, 0, "x")))
        val reader = CwxReader(CwxEncoder.encode(transcript))
        assertEquals(2500L, reader.durationMs)
        assertEquals(3L, reader.seekCount)
    }

    @Test fun keepsFifteenDeclaredSpeakersAndAddsUnknown() {
        val speakers = (1..17).map { CwxSpeaker("S$it") }
        val words = listOf(CwxWord(0, 10, 14, "a"), CwxWord(10, 20, 15, "b"), CwxWord(20, 30, 16, "c"))
        val reader = CwxReader(CwxEncoder.encode(CwxTranscript("t", 0, speakers, words)))
        assertEquals(16, reader.speakerCount)
        assertEquals("Unknown", reader.speakers[15].label)
        assertEquals(listOf(14, 15, 15), reader.wordsFrom(0).map { it.speaker })
        assertEquals(listOf(1, 1, 0), reader.wordsFrom(0).map { it.flags })
    }

    @Test fun packsRgb565() {
        assertEquals(0xFFFF, CwxEncoder.rgb565(0xFFFFFF))
        assertEquals(0xF800, CwxEncoder.rgb565(0xFF0000))
        assertEquals(0x07E0, CwxEncoder.rgb565(0x00FF00))
        assertEquals(0x001F, CwxEncoder.rgb565(0x0000FF))
        assertEquals(0x3339, CwxEncoder.rgb565(0x3366CC))
    }

    @Test fun sanitizesLikePython() {
        assertEquals("a b", text(" \u0085a\u0001\u0002b\u3000 ", 47))
        assertEquals("'\"-...", text("\u2019\u201C\u2014\u2026", 47))
        // A cut never splits a character: "é" is 2 bytes.
        assertEquals("abé", text("abéé", 5))
        assertEquals("ab", text("abéé", 3))
        assertEquals("", text("\u0001\u0002", 47))
    }

    private fun text(value: String, max: Int) = CwxEncoder.sanitize(value, max).toString(Charsets.UTF_8)

    private fun mixed(): CwxTranscript {
        val cases = JSONObject(resource("cwx-cases.json").toString(Charsets.UTF_8)).getJSONArray("cases")
        return transcript(cases.getJSONObject(0))
    }

    private fun transcript(case: JSONObject): CwxTranscript {
        val speakers = case.getJSONArray("speakers").let { array ->
            (0 until array.length()).map { i ->
                val speaker = array.getJSONObject(i)
                val rgb = if (speaker.isNull("rgb")) null else speaker.getString("rgb").removePrefix("#").toInt(16)
                CwxSpeaker(speaker.getString("label"), rgb)
            }
        }
        val words = case.getJSONArray("words").let { array ->
            (0 until array.length()).map { i ->
                val word = array.getJSONObject(i)
                CwxWord(
                    startMs = word.getLong("startMs"),
                    endMs = word.getLong("endMs"),
                    speakerIndex = word.getInt("speakerIndex"),
                    text = word.getString("text"),
                    turnStart = word.getBoolean("turnStart"),
                    lowConfidence = word.getBoolean("lowConfidence"),
                )
            }
        }
        return CwxTranscript(case.getString("title"), case.getLong("durationMs"), speakers, words)
    }

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/recorder/$name")) { "Missing $name" }.use { it.readBytes() }
}
