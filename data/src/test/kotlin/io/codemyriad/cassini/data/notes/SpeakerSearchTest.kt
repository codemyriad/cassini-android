package io.codemyriad.cassini.data.notes

import io.codemyriad.cassini.core.format.Word
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SpeakerSearchTest {
    @Test fun earliestMatchingRunWinsEvenWhenCanonicalRunsAreNotStartSorted() = runBlocking {
        val words = listOf(
            Word("Ada", 10_000, 10_400, "delivery"),
            Word("Ben", 12_000, 12_400, "unrelated"),
            Word("Ada", 0, 400, "delivery"),
            Word("Ada", 400, 800, "first"),
        )
        SpeakerSearch().use { matcher ->
            assertEquals("\u0002delivery\u0003 first", matcher.snippet("\"deliver*\"", words, setOf("Ada"), true))
            assertEquals(null, matcher.snippet("\"deliver*\"", words, setOf("Ben"), true))
        }
    }

    @Test fun anElevenHourTranscriptUsesNearLinearThreadCpu() = runBlocking {
        // Android's compile boot classpath lacks java.management; the test runs on the host JDK.
        val type = Class.forName("java.lang.management.ThreadMXBean")
        val timer = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        assertEquals(true, type.getMethod("isCurrentThreadCpuTimeSupported").invoke(timer))
        type.getMethod("setThreadCpuTimeEnabled", Boolean::class.javaPrimitiveType).invoke(timer, true)
        val readCpu = type.getMethod("getCurrentThreadCpuTime")
        fun cpu(): Long = readCpu.invoke(timer) as Long
        fun words(count: Int) = List(count) { index ->
            Word(if (index / 150 % 2 == 0) "Ada" else "Ben", index * 400L, index * 400L + 300, "delivery")
        }
        suspend fun measure(input: List<Word>): Long {
            val start = cpu()
            SpeakerSearch().use { matcher ->
                assertNotNull(matcher.snippet("\"deliver*\"", input, setOf("Ada", "Ben"), true))
            }
            return cpu() - start
        }
        measure(words(8000)) // Warm SQLite/JIT; no timing or sleeps control the test.
        val half = words(55_000)
        val full = words(110_000)
        val small = minOf(measure(half), measure(half))
        val large = minOf(measure(full), measure(full))
        println("SpeakerSearch thread CPU ns: N=$small, 2N=$large")
        if (System.getenv("CASSINI_PERF_GATE") == "1") {
            // Sorting/FTS may add O(N log N); 3x plus 100 ms covers fixed startup, not per-pair scans.
            assertTrue("CPU ns: N=$small, 2N=$large", large <= small * 3 + 100_000_000L)
            assertTrue("100k-word CPU budget: $large ns", large < 10_000_000_000L)
        }
    }
}
