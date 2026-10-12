package org.cassini.core.format

import org.cassini.core.checkpoint.LongRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WordBodyBudgetTest {
    private fun actual(words: List<Word>, language: String = "") =
        Transcript(words, language).json().toByteArray(Charsets.UTF_8).size.toLong()

    @Test fun theCountIsAnUpperBoundWithinOnePercent() {
        val words = LongRun().words(LongRun.HOUR_MS)
        val real = actual(words)
        val counted = WordBodyBudget.bytes(words)
        assertTrue("$counted < $real", counted >= real)
        assertTrue("$counted is far above $real", counted <= real + real / 100)
        assertTrue(WordBodyBudget.bytes(words, "it") >= actual(words, "it"))
    }

    @Test fun escapesAndWideCharactersCountAsTheyAreWritten() {
        val words = listOf(
            Word("s1", 0, 1, "\"quoted\""),
            Word("s1", 1, 2, "back\\slash"),
            Word("s1", 2, 3, "e/o"),
            Word("s1", 3, 4, "giovedì"),
            Word("s1", 4, 5, "東京"),
            Word("s1", 5, 6, "😀"),
            Word("s1", 6, 7, "a\u0001b"),
        )
        assertTrue(WordBodyBudget.bytes(words) >= actual(words))
        val one = WordBodyBudget.bytes(listOf(Word("s1", 0, 0, "abc")))
        assertEquals(WordBodyBudget.bytes(emptyList()) + 87 + 2 + 1 + 1 + 3, one)
    }

    @Test fun eightHoursOfSpeechFitAndFourteenDoNot() {
        assertTrue(WordBodyBudget.fits(LongRun().words(8 * LongRun.HOUR_MS)))
        assertFalse(WordBodyBudget.fits(LongRun().words(14 * LongRun.HOUR_MS)))
    }

    /** A body at the budget is accepted by the payload codec and stays well inside the header limit once encoded. */
    @Test fun aBodyAtTheBudgetEncodes() {
        val all = LongRun().words(13 * LongRun.HOUR_MS)
        var low = 0
        var high = all.size
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (WordBodyBudget.fits(all.subList(0, mid))) low = mid else high = mid - 1
        }
        val words = all.subList(0, low)
        val body = Transcript(words).json()
        assertTrue(body.toByteArray(Charsets.UTF_8).size <= CassiniPayload.MAX_RAW)
        val (ref, tags) = CassiniPayload.encode("CASSINI_TX_WORDS_PAYLOAD_", CassiniPayload.WORD_MIME, body)
        assertTrue(ref.getInt("chunkCount") <= 4096)
        assertTrue(tags.sumOf { it.length + 4 } < OggOpus.MAX_HEADER_BYTES / 4)
        assertTrue("${words.size} words", words.size in 100_000..120_000)
    }
}
