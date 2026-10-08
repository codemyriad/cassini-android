package org.cassini.android

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/** Appending settled words in pieces shows what one full build shows, at a cost that grows with the new words only. */
class TranscriptTextTest {
    private fun words(count: Int, seed: Int = 3): List<Word> {
        val random = Random(seed)
        var at = 0L
        return List(count) { index ->
            val start = at + random.nextLong(0, 200)
            val end = start + random.nextLong(80, 600)
            at = end
            val text = "w$index" + if (random.nextInt(10) == 0) "." else ""
            Word(if (random.nextInt(30) == 0) "spk_2" else "spk_1", start, end, text)
        }
    }

    private fun build(words: List<Word>, label: ((Word) -> String)? = null) = TranscriptText(label).also { it.append(words) }

    @Test fun appendingDeltasMatchesOneFullBuild() {
        val all = words(3_000)
        val label = { word: Word -> "[${word.speaker}]" }
        listOf<((Word) -> String)?>(null, label).forEach { labeller ->
            val full = build(all, labeller)
            val pieces = TranscriptText(labeller)
            val random = Random(11)
            var at = 0
            while (at < all.size) {
                val n = minOf(random.nextInt(0, 60), all.size - at)
                val length = pieces.text.length
                assertEquals(length, pieces.append(all.subList(at, at + n)))
                at += n
            }
            assertEquals(full.text.toString(), pieces.text.toString())
            assertEquals(full.size, pieces.size)
            for (index in 0 until full.size) {
                assertEquals(full.start(index), pieces.start(index))
                assertEquals(full.end(index), pieces.end(index))
                assertEquals(all[index].text, full.text.substring(full.start(index), full.end(index)))
            }
        }
    }

    @Test fun separatorsFollowSentencesAndSpeakers() {
        val text = build(listOf(Word("a", 0, 1, "Ciao."), Word("a", 1, 2, "Come"), Word("a", 2, 3, "va"), Word("b", 3, 4, "Bene")),
            { "<${it.speaker}>" })
        assertEquals("<a>\nCiao.\n\nCome va\n\n<b>\nBene", text.text.toString())
    }

    @Test fun theProvisionalTailJoinsTheLastSettledWord() {
        val text = build(listOf(Word("a", 0, 1, "uno.")))
        assertEquals("\n\ndue tre", text.tail(listOf(Word("a", 1, 2, "due"), Word("a", 2, 3, "tre"))))
        assertEquals("due", TranscriptText().tail(listOf(Word("a", 1, 2, "due"))))
        assertEquals(1, text.size)
    }

    @Test fun wordAtMatchesALinearScan() {
        val all = words(2_000) + listOf(Word("spk_1", 10_000_000, 10_000_500, "x"), Word("spk_1", 10_000_000, 10_000_200, "y"))
        val text = build(all)
        val random = Random(5)
        repeat(5_000) {
            val time = random.nextLong(-100, all.last().endMs + 1_000)
            assertEquals(all.indexOfFirst { time >= it.startMs && time < it.endMs }, text.wordAt(time))
        }
        assertEquals(-1, TranscriptText().wordAt(0))
    }

    @Test fun appendingTwentyThousandWordsStaysLinear() {
        val all = words(20_000)
        val began = System.nanoTime()
        val text = TranscriptText()
        all.chunked(20).forEach { text.append(it) }
        val ms = (System.nanoTime() - began) / 1_000_000
        assertEquals(20_000, text.size)
        assertTrue("took $ms ms", ms < 1_000)
    }
}
