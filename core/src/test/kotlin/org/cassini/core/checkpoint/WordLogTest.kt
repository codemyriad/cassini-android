package org.cassini.core.checkpoint

import org.cassini.core.format.Word
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WordLogTest {
    private val run = LongRun()

    @Test fun onlyWordsTenSecondsBeforeTheOpenWindowSettle() {
        val words = listOf(Word("s1", 0, 400, "a"), Word("s1", 500, 9_999, "b"), Word("s1", 10_000, 10_500, "c"))
        assertEquals(0, WordLog.settled(words, 10_399))
        assertEquals(1, WordLog.settled(words, 10_400))
        assertEquals(2, WordLog.settled(words, 19_999))
        assertEquals(3, WordLog.settled(words, 20_500))
        // The scan continues from a known count.
        assertEquals(3, WordLog.settled(words, 20_500, from = 2))
        // A later word that ends early does not settle past an earlier one that does not.
        val overlapping = listOf(Word("s1", 0, 30_000, "long"), Word("s1", 100, 200, "short"))
        assertEquals(0, WordLog.settled(overlapping, 20_000))
    }

    @Test fun linesRoundTripWithEscapesAndWideCharacters() {
        val words = listOf(
            Word("diar_x_1", 0, 240, "\"Ciao\","),
            Word("diar_x_2", 240, 480, "e/o"),
            Word("diar_x_1", 480, 720, "giovedì\\"),
            Word("diar_x_1", 720, 960, "東京😀"),
        )
        val bytes = WordLog.lines(words, 0, words.size)
        assertEquals(words, WordLog.read(bytes, words.size))
        assertEquals(words.subList(1, 3), WordLog.read(WordLog.lines(words, 1, 3), 2))
        assertEquals(4, String(bytes, Charsets.UTF_8).count { it == '\n' })
    }

    @Test fun aDamagedOrShortLogIsRefused() {
        val bytes = WordLog.lines(listOf(Word("s1", 0, 1, "a"), Word("s1", 1, 2, "b")), 0, 2)
        assertThrows(IllegalArgumentException::class.java) { WordLog.read(bytes, 3) }
        assertThrows(IllegalArgumentException::class.java) { WordLog.read(bytes.copyOf(bytes.size - 1), 2) }
        assertThrows(IllegalArgumentException::class.java) { WordLog.read("[\"s1\",0]\n".toByteArray(), 1) }
        assertThrows(IllegalArgumentException::class.java) { WordLog.read("{}\n".toByteArray(), 1) }
        assertThrows(IllegalArgumentException::class.java) { WordLog.read("[\"s1\",5,4,\"a\"]\n".toByteArray(), 1) }
        assertThrows(IllegalArgumentException::class.java) { WordLogRef(1, 0) }
        assertEquals(emptyList<Word>(), WordLog.read(ByteArray(0), 0))
    }

    /**
     * A synthetic 10 h run, saved as the repository saves it: each window appends only its new words, and the log
     * and the checkpoint give back every word. The checkpoint keeps its size, except for the diarizer turns, which
     * stay inline: about 22 bytes for each turn (4,130 turns in this run).
     */
    @Test fun tenHoursOfCheckpointsGrowLinearlyAndRestoreEveryWord() {
        val duration = 10 * LongRun.HOUR_MS
        val all = run.words(duration)
        var end = 0
        val log = java.io.ByteArrayOutputStream()
        var ref = WordLogRef.EMPTY
        var appended = 0L
        var largestAppend = 0
        val checkpointBytes = mutableMapOf<Long, Int>()
        var last: JSONObject? = null
        for (doneMs in run.windowEnds(duration)) {
            while (end < all.size && all[end].endMs <= doneMs) end++
            val words = all.subList(0, end)
            val state = run.checkpoint(doneMs, duration, words)
            val windowStartMs = state.recognition.chunks.start * 1000 / LongRun.RATE
            val settled = WordLog.settled(words, windowStartMs, ref.count)
            val lines = WordLog.lines(words, ref.count, settled)
            log.write(lines)
            appended += lines.size
            largestAppend = maxOf(largestAppend, lines.size)
            ref = if (settled == 0) WordLogRef.EMPTY else WordLogRef(settled, log.size().toLong())
            // Only a sample of windows is encoded: the encoding is what does not grow.
            if (doneMs < 2 * LongRun.WINDOW_MS || doneMs + LongRun.STEP_MS > duration || doneMs % LongRun.HOUR_MS < LongRun.STEP_MS) {
                val json = CheckpointState.encode(state, ref)
                checkpointBytes[doneMs] = json.toString().length
                last = json
            }
        }
        val sizes = checkpointBytes.values
        assertTrue("checkpoint sizes $sizes", sizes.max() - sizes.min() < 128 * 1024)
        assertEquals(log.size().toLong(), appended)
        assertTrue("largest append $largestAppend", largestAppend < 64 * 1024)
        val json = JSONObject(checkNotNull(last).toString())
        val back = CheckpointState.decode(json, WordLog.read(log.toByteArray().copyOf(ref.bytes.toInt()), ref.count))
        assertEquals(all.subList(0, end), back.recognition.chunks.words)
        assertEquals(end, CheckpointState.wordCount(json))
    }
}
