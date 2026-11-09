package io.codemyriad.cassini.core.export

import io.codemyriad.cassini.core.format.Transcript
import io.codemyriad.cassini.core.format.Word
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingTurnsTest {
    @Test fun allSharedViewerVectorsMatchEveryNormativeField() {
        for (fixture in fixtures) {
            val words = Transcript.fromJson(resource("$fixture.words.json")).words
            val expected = JSONObject(resource("$fixture.expected.json"))
            assertEquals(REFERENCE, expected.getJSONObject("source").getString("commit"))
            val reading = ReadingTurns.of(words)
            val actual = reading.rows.map { row ->
                JSONObject().put("speaker", row.speaker).put("continuation", row.continuation)
                    .put("startMs", row.startMs).put("endMs", row.endMs)
                    .put("over", JSONArray(row.over)).put("crosstalk", row.crosstalk)
                    .put(
                        "members",
                        JSONArray(
                            row.members.map { member ->
                                JSONObject().put(
                                    "kind",
                                    if (member.interjectionSpeaker == null) "words" else "interjection",
                                )
                                    .put("wordIndices", JSONArray(member.wordIndices))
                                    .put("text", member.wordIndices.joinToString(" ") { reading.words[it].text })
                                    .apply {
                                        if (member.interjectionSpeaker != null) {
                                            put("speaker", member.interjectionSpeaker)
                                            put("crosstalk", member.crosstalk)
                                        }
                                    }
                            },
                        ),
                    )
            }
            assertTrue(
                "$fixture: expected ${expected.getJSONArray("rows")}, got $actual",
                expected.getJSONArray("rows").similar(JSONArray(actual)),
            )
            assertEquals(
                "$fixture: every canonical atom exactly once",
                words.indices.toList(),
                reading.rows.flatMap { it.members }.flatMap { it.wordIndices }.sorted(),
            )
            assertEquals(words.map { it.text }, reading.words.map { it.text })
            assertEquals(words.map { it.startMs }, reading.words.map { it.startMs })
            assertEquals("$fixture: statistics keep every whole run", reading.runs, ReadingTurns.wholeRuns(words))
            assertEquals(
                "$fixture: measured ends stay attested in whole runs",
                ReadingTurns.of(words, endsBoundedByAudio = true).runs,
                ReadingTurns.wholeRuns(words, endsBoundedByAudio = true),
            )
        }
    }

    @Test fun timingRepairIsDisplayOnlyAndRespectsMeasuredEnds() {
        val words = listOf(Word("a", 0, 4000, "Held.”"))
        assertEquals(1000L, ReadingTurns.of(words).words.single().endMs)
        assertEquals(4000L, ReadingTurns.of(words, endsBoundedByAudio = true).words.single().endMs)
        assertEquals(4000L, words.single().endMs)
    }

    @Test fun medianExcludesCandidatesAndFallsBackToMeetingThenSpeaker() {
        val reference = (0..7).map { Word("a", it * 2000L, it * 2000L + 600, "reference") }
        val candidate = Word("b", 20000, 25000, "Yes.")
        assertEquals(22400L, ReadingTurns.of(reference + candidate).words.last().endMs)
        val own = (0..7).map { Word("b", 30000 + it * 2000L, 30300 + it * 2000L, "reference") }
        assertEquals(21200L, ReadingTurns.of(reference + candidate + own).words[8].endMs)
    }

    @Test fun crosstalkExcludesChipWordsFromHostAndKeepsChipJudgement() {
        val words = Transcript.fromJson(resource("acknowledgment.words.json")).words
        val chip = setOf(7, 8)
        val reading = ReadingTurns.of(words, lowConfidenceWords = chip).rows.single()
        assertFalse(reading.crosstalk)
        assertTrue(reading.members.single { it.interjectionSpeaker != null }.crosstalk)
        val host = ReadingTurns.of(words, lowConfidenceWords = words.indices.toSet() - chip).rows.single()
        assertTrue(host.crosstalk)
        assertFalse(host.members.single { it.interjectionSpeaker != null }.crosstalk)
    }

    @Test fun interjectionAudibleLimitIsInclusive() {
        fun rows(duration: Long) = ReadingTurns.of(
            listOf(
                Word("a", 0, 1000, "before"),
                Word("b", 900, 900 + duration, "remark"),
                Word("a", 1000, 4000, "after"),
            ),
        ).rows
        assertEquals(1, rows(2000).size)
        assertEquals("b", rows(2000).single().members[1].interjectionSpeaker)
        assertEquals(2, rows(2001).size)
        assertEquals(listOf("b"), rows(2001).first().over)
    }

    @Test fun rejoinSeamIsInclusiveAndNeedsContinuity() {
        fun rows(gap: Long, middleStart: Long) = ReadingTurns.of(
            listOf(
                Word("a", 0, 1000, "before"),
                Word("b", middleStart, 1200, "remark"),
                Word("a", 1000 + gap, 3000, "after"),
            ),
        ).rows
        assertEquals(1, rows(400, 1000).size)
        assertEquals(3, rows(401, 1000).size)
        assertEquals(3, rows(400, 1050).size) // clean air on both sides is a reply, not continuity
    }

    @Test fun separatedSmallIntersectionsAccumulateBeforeTheCredibilityFloor() {
        val words = listOf(
            Word("a", 0, 100, "First"),
            Word("b", 0, 100, "Another"),
            Word("a", 200, 300, "part"),
            Word("b", 200, 300, "part"),
        )
        val rows = ReadingTurns.of(words).rows
        assertEquals(listOf(listOf("b"), listOf("a")), rows.map { it.over })
        assertEquals(listOf(listOf(0, 2), listOf(1, 3)), rows.map { row -> row.members.flatMap { it.wordIndices } })
    }

    companion object {
        private const val REFERENCE = "1111155cb0017b47c072a8662c54f28ad7c44223"
        val fixtures =
            listOf("acknowledgment", "competing", "monologue", "overlapping-turns", "brief-reply", "seam-gap")
        fun resource(name: String): String =
            checkNotNull(ReadingTurnsTest::class.java.getResourceAsStream("/interjections/$name"))
                .bufferedReader().use { it.readText() }
    }
}
