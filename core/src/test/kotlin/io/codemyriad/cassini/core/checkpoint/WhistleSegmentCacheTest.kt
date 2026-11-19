package io.codemyriad.cassini.core.checkpoint

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Tests [WhistleSegmentCache] atomic persistence, validation, and rejection of corrupt or mismatched files. */
class WhistleSegmentCacheTest {
    @get:Rule val temp = TemporaryFolder()

    private val pipeline = "whistle@ca5287601bef25af26dcf2e1b2bdc0843a7c19e5"
    private val sha1 = "a".repeat(64)
    private val sha2 = "b".repeat(64)
    private val json1 = """{"words":[{"word":"Hello","start":0.01,"end":0.12}]}""".toByteArray(Charsets.UTF_8)
    private val json2 = """{"words":[{"word":"world","start":0.02,"end":0.18}]}""".toByteArray(Charsets.UTF_8)

    @Test fun roundTripsVerifiedEntriesAtomically() {
        val file = temp.newFile("whistle-segments.json")
        val cache = WhistleSegmentCache(pipeline)
        assertTrue(cache.put(sha1, 16_000, json1))
        assertTrue(cache.put(sha2, 24_000, json2))
        cache.writeTo(file)

        val loaded = WhistleSegmentCache.readFrom(file, pipeline)
        assertEquals(2, loaded.size)
        assertArrayEquals(json1, loaded.get(sha1, 16_000))
        assertArrayEquals(json2, loaded.get(sha2, 24_000))
        // Wrong sample count misses safely even for the same hash key.
        assertNull(loaded.get(sha1, 16_001))
    }

    @Test fun rejectsWrongPipelineCorruptJsonAndInvalidEntriesCleanly() {
        val file = temp.newFile("whistle-segments.json")
        val cache = WhistleSegmentCache(pipeline)
        assertTrue(cache.put(sha1, 16_000, json1))
        // Malformed entries are ignored.
        assertEquals(false, cache.put("not-hex", 16_000, json1))
        assertEquals(false, cache.put(sha2, 0, json1))
        assertEquals(false, cache.put(sha2, 16_000, "not json".toByteArray()))
        cache.writeTo(file)

        // Different pipeline ignores cached entries.
        val wrongPipeline = WhistleSegmentCache.readFrom(file, "whistle@other")
        assertEquals(0, wrongPipeline.size)
        assertNull(wrongPipeline.get(sha1, 16_000))

        // Corrupt JSON on disk returns empty cache without throwing.
        file.writeText("{truncated")
        assertEquals(0, WhistleSegmentCache.readFrom(file, pipeline).size)
    }

    @Test fun rejectsMalformedWordEntriesOnPutAndDecode() {
        val cache = WhistleSegmentCache(pipeline)
        val malformed = listOf(
            """{"words":[{}]}""",
            """{"words":[42]}""",
            """{"words":[{"word":"","start":0.0,"end":0.1}]}""",
            """{"words":[{"word":"   ","start":0.0,"end":0.1}]}""",
            """{"words":[{"word":"two words","start":0.0,"end":0.1}]}""",
            """{"words":[{"word":"ok","start":0.5,"end":0.1}]}""",
            """{"words":[{"word":"ok","start":"0.0","end":0.1}]}""",
            """{"words":[{"word":"ok","start":0.0}]}""",
            """{"words":[{"word":123,"start":0.0,"end":0.1}]}""",
        )
        for (raw in malformed) {
            val bytes = raw.toByteArray(Charsets.UTF_8)
            assertEquals("Expected rejection for $raw", false, WhistleSegmentCache.isValidSegmentJson(bytes))
            assertEquals("Expected put rejection for $raw", false, cache.put(sha2, 16_000, bytes))
        }
        // Empty words array is valid (silent/unrecognized segment).
        assertTrue(cache.put(sha1, 16_000, """{"words":[]}""".toByteArray(Charsets.UTF_8)))

        // Crafted cache file on disk with {"words":[{}]} is dropped on readFrom.
        val file = temp.newFile("malformed-words.json")
        val encodedBad = java.util.Base64.getEncoder().encodeToString("""{"words":[{}]}""".toByteArray(Charsets.UTF_8))
        val encodedGood = java.util.Base64.getEncoder().encodeToString(json1)
        file.writeText(
            """{"format":"${WhistleSegmentCache.FORMAT}","pipeline":"$pipeline","segments":{""" +
                """"$sha2":{"sampleCount":16000,"jsonBase64":"$encodedBad"},""" +
                """"$sha1":{"sampleCount":16000,"jsonBase64":"$encodedGood"}}}""",
        )
        val loaded = WhistleSegmentCache.readFrom(file, pipeline)
        assertEquals(1, loaded.size)
        assertNull(loaded.get(sha2, 16_000))
        assertArrayEquals(json1, loaded.get(sha1, 16_000))
    }
}
