package org.cassini.core.library

import org.cassini.core.format.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewTextTest {
    private fun turn(speaker: String, startMs: Long, text: String, msPerWord: Long = 350): List<Word> =
        text.split(' ').mapIndexed { i, word ->
            Word(speaker, startMs + i * msPerWord, startMs + (i + 1) * msPerWord - 50, word)
        }

    // The words of board 03 ("Call with supplier"). Paolo's turn starts at 11:41 and "quindi" plays at 11:44.
    private val board03 = turn(
        "spk_0", 692_000,
        "Ok, then let's confirm the quantities first. We said four hundred units for the first batch, right?",
    ) + turn(
        "spk_1", 701_000,
        "Sì, quattrocento. Il problema è la seconda consegna: quindi la consegna slitta a giovedì, " +
            "but the price stays as agreed in the March order.",
    ) + turn("spk_2", 740_000, "Can you send that in writing? I need it for the purchase order before Friday.")

    @Test fun reproducesBoard00() {
        val preview = PreviewText.of(board03, (11 * 60 + 44) * 1000L)!!
        val board00 = "quindi la consegna slitta a giovedì, but the price stays as agreed in the March order"
        assertEquals("…$board00…", preview.display)
        assertEquals(board00, preview.text)
        assertTrue(preview.leadingEllipsis)
        assertTrue(preview.trailingEllipsis)
    }

    @Test fun positionZeroGivesTheTranscriptStart() {
        val preview = PreviewText.of(board03, 0)!!
        assertEquals(
            "Ok, then let's confirm the quantities first. We said four hundred units for the first batch, right? " +
                "Sì, quattrocento. Il problema è la seconda consegna: quindi…",
            preview.display,
        )
        assertFalse(preview.leadingEllipsis)
        assertTrue(preview.text.length <= PreviewText.LIMIT)
        // Before the first word and inside it are the start too.
        assertEquals(preview, PreviewText.of(board03, 500_000))
        assertEquals(preview, PreviewText.of(board03, 692_100))
    }

    @Test fun aPauseStartsAtTheNextWord() {
        val preview = PreviewText.of(board03, 735_000)!!
        assertEquals("…Can you send that in writing? I need it for the purchase order before Friday.", preview.display)
        assertTrue(preview.leadingEllipsis)
        assertFalse(preview.trailingEllipsis)
    }

    @Test fun endOfTranscript() {
        val last = board03.last()
        assertEquals("…Friday.", PreviewText.of(board03, last.startMs)!!.display)
        val after = PreviewText.of(board03, last.endMs + 60_000)!!
        assertEquals(Preview("Friday.", leadingEllipsis = true, trailingEllipsis = false), after)
    }

    @Test fun cutAtAWordWithPunctuationDropped() {
        val long = turn("spk_0", 0, (1..40).joinToString(" ") { "parola$it," })
        val preview = PreviewText.of(long, long[5].startMs)!!
        assertTrue(preview.display, preview.text.length <= PreviewText.LIMIT)
        assertTrue(preview.text.startsWith("parola6, parola7, "))
        // Whole words only: the excerpt is a prefix of the words from the playing one.
        val words = long.drop(5).map { it.text.trimEnd(',') }
        assertTrue(preview.text, words.joinToString(", ").startsWith(preview.text))
        assertTrue(preview.display.endsWith("…"))
        assertFalse(preview.display, preview.display.endsWith(",…"))
        // One more word would not fit (the dropped comma counted).
        val next = long[5 + preview.text.split(' ').size].text
        assertTrue((preview.text + ",").length + 1 + next.length > PreviewText.LIMIT)
    }

    @Test fun sentencePunctuationIsDroppedOnlyWhenCut() {
        val words = turn("spk_0", 0, "Fine. Next.") + turn("spk_1", 2_000, "Yes!")
        assertEquals("…Next…", PreviewText.of(words, words[1].startMs)!!.display)
        assertEquals("…Yes!", PreviewText.of(words, words[2].startMs)!!.display)
        assertEquals("Fine. Next. Yes!", PreviewText.of(words, 0)!!.display)
    }

    @Test fun shortTranscriptHasNoEllipses() {
        val words = turn("spk_0", 0, "Ciao mondo.")
        val whole = Preview("Ciao mondo.", leadingEllipsis = false, trailingEllipsis = false)
        assertEquals(whole, PreviewText.of(words, 0))
    }

    @Test fun noTranscriptNoPreview() {
        assertNull(PreviewText.of(emptyList(), 0))
        assertNull(PreviewText.of(emptyList(), 10_000))
    }

    @Test fun aGiantWordIsCutOnACodePoint() {
        val giant = "a".repeat(PreviewText.LIMIT - 1) + "😀" + "tail"
        val preview = PreviewText.of(listOf(Word("spk_0", 0, 1_000, giant)), 0)!!
        assertEquals("a".repeat(PreviewText.LIMIT - 1), preview.text)
        assertTrue(preview.trailingEllipsis)
    }

    @Test fun storedPreviewReadsBack() {
        val preview = PreviewText.of(board03, 704_000)!!
        val parsed = SearchQuery.highlights(preview.display)
        assertEquals(Highlighted(preview.text, emptyList(), leadingEllipsis = true, trailingEllipsis = true), parsed)
    }
}
