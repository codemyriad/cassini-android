package io.codemyriad.cassini.core.export

import io.codemyriad.cassini.core.checkpoint.LongRun
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory

/** The 11-hour export shape and CPU work, independent of time spent descheduled by the host. */
class LongExportTest {
    private val run = LongRun()
    private val hours = 11L
    private val words = run.words(hours * LongRun.HOUR_MS)
    private val labels = (1..LongRun.SPEAKERS).associate { "${run.prefix}$it" to "Speaker $it" }
    private val long = ExportTranscript("Conference day", hours * LongRun.HOUR_MS, words, labels)

    // This timeout only stops a hung test; CPU work and scaling below are the performance criteria.
    @Test(timeout = HANG_GUARD_MS)
    fun textSrtAndVttWriteEveryWordOfAnElevenHourTranscript() {
        val clock = ManagementFactory.getThreadMXBean()
        assertTrue("JDK 17 must provide calling-thread CPU time", clock.isCurrentThreadCpuTimeSupported)
        val wasEnabled = clock.isThreadCpuTimeEnabled
        if (!wasEnabled) clock.isThreadCpuTimeEnabled = true
        try {
            val halfMs = hours * LongRun.HOUR_MS / 2
            val half = ExportTranscript("Conference day", halfMs, run.words(halfMs), labels)
            val warmMs = LongRun.HOUR_MS
            exports(ExportTranscript("Conference day", warmMs, run.words(warmMs), labels)).forEach(::assertExportShape)
            val halfCpu = ArrayList<Long>()
            val fullCpu = ArrayList<Long>()
            var fullExports = emptyList<String>()
            repeat(SAMPLES) { round ->
                // Alternate the order so compilation does not always favour the same size.
                val order = if (round % 2 == 0) listOf(half, long) else listOf(long, half)
                for (input in order) {
                    val began = clock.currentThreadCpuTime
                    val output = exports(input)
                    val cpu = clock.currentThreadCpuTime - began
                    if (perfGateEnabled()) {
                        assertTrue("export CPU $cpu ns exceeds the work budget", cpu < CPU_LIMIT_NS)
                    }
                    output.forEach(::assertExportShape)
                    if (input === long) {
                        fullCpu += cpu
                        fullExports = output
                    } else {
                        halfCpu += cpu
                    }
                }
            }
            val small = halfCpu.sorted()[SAMPLES / 2]
            val large = fullCpu.sorted()[SAMPLES / 2]
            val growth = if (small > 0) large.toDouble() / small else Double.NaN
            println("export CPU ns: half=$halfCpu full=$fullCpu; median 2N/N=$growth")
            if (perfGateEnabled()) {
                assertTrue("CPU clock must resolve the half-size export", small > 0)
                assertTrue("CPU work grows $growth times for twice the words", growth < MAX_CPU_GROWTH)
            }
            val (text, srt, vtt) = fullExports
            val last = words.last().text
            assertTrue(text.trimEnd().endsWith(last))
            assertTrue("SRT clock past 10 h", srt.contains("10:59:"))
            assertTrue("VTT clock past 10 h", vtt.contains("10:59:"))
            assertTrue(text.contains("10:"))
        } finally {
            if (!wasEnabled) clock.isThreadCpuTimeEnabled = false
        }
    }

    private fun exports(input: ExportTranscript) = listOf(
        TextExport.write(input, "11 Oct 2026", "No words"),
        SrtWriter.write(input),
        VttWriter.write(input),
    )

    private fun perfGateEnabled(): Boolean = System.getenv("CASSINI_PERF_GATE") == "1"

    private companion object {
        const val SAMPLES = 3
        const val CPU_LIMIT_NS = 30_000_000_000L

        // Disjoint runs must avoid the approximately 4x growth of an all-pairs interval scan.
        const val MAX_CPU_GROWTH = 3.5
        const val HANG_GUARD_MS = 300_000L
    }
}
