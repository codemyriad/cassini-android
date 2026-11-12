package io.codemyriad.cassini.note

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSearchTest {
    private fun matches(texts: List<Pair<String, String>>, query: String) =
        TextSearch.matches(texts, query, { it.first }, { it.second })

    @Test fun searchIgnoresCaseAndAccents() {
        val turns = listOf("w0" to "Café DELIVERY", "w2" to "cafe delivery")
        val cafe = matches(turns, "cafe")
        assertEquals(2, cafe.size)
        assertEquals("w0", cafe[0].turnId)
        assertEquals("w2", cafe[1].turnId)
        assertEquals(0..3, cafe[0].range)
        assertEquals(0..3, cafe[1].range)
        assertEquals(listOf(5..12, 5..12), matches(turns, "Delivery").map { it.range })
        assertTrue(matches(turns, "  ").isEmpty())
        assertTrue(matches(turns, "tomorrow").isEmpty())
    }

    @Test fun aDecomposedAccentKeepsTheRangeOfTheSourceText() {
        val match = matches(listOf("w0" to "Café aperto"), "café").single()
        assertEquals("the combining mark belongs to the match", 0..4, match.range)
    }

    @Test fun supplementaryCharactersKeepUtf16SourceRanges() {
        val match = matches(listOf("w0" to "😀 Café"), "cafe").single()
        assertEquals(3..6, match.range)
    }
}
