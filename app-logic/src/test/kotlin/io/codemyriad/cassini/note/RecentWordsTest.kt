package io.codemyriad.cassini.note

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecentWordsTest {
    @Test fun aReadKeepsTheVersionRecentAndSignaturesStayDistinct() {
        val cache = RecentWords()
        val first = RecentWords.Key("a", "v", "one", 0)
        val second = first.copy(noteId = "b")
        cache.put(first, emptyList())
        cache.put(second, emptyList())
        assertEquals(emptyList<Any>(), cache.get(first))
        cache.put(first.copy(signature = "two"), emptyList())
        assertNull(cache.get(second))
        assertEquals(emptyList<Any>(), cache.get(first))
    }
}
