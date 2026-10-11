package org.cassini.core.checkpoint

import org.cassini.core.format.CassiniPayload
import org.cassini.core.format.Transcript
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The measurements of docs/architecture.md section 3.7, on the JVM. They take minutes, so they run only when the
 * environment variable CASSINI_MEASURE is set:
 *
 *     CASSINI_MEASURE=1 ./gradlew --configure-on-demand :core:test --tests '*LongRunMeasurementTest' -i
 *
 * They print what they measure and assert nothing that a slower machine could fail.
 */
class LongRunMeasurementTest {
    private val run = LongRun()

    private fun measuring() = assumeTrue("Set CASSINI_MEASURE to run", System.getenv("CASSINI_MEASURE") != null)

    /** checkpoint.json as the job writes it after each window of a 10 h run. */
    @Test fun checkpointWritesOfATenHourRun() {
        measuring()
        val duration = HOURS * LongRun.HOUR_MS
        val all = run.words(duration)
        var end = 0
        var written = 0L
        var nanos = 0L
        var windows = 0
        println("checkpoint.json, all words inline (version 1)")
        println("hour  words  file_bytes  written_total_MB  encode_total_s")
        var nextHour = LongRun.HOUR_MS
        for (doneMs in run.windowEnds(duration)) {
            while (end < all.size && all[end].endMs <= doneMs) end++
            val state = run.checkpoint(doneMs, duration, all.subList(0, end))
            val started = System.nanoTime()
            val bytes = CheckpointState.encode(state).toString().toByteArray(Charsets.UTF_8).size
            nanos += System.nanoTime() - started
            written += bytes
            windows++
            if (doneMs >= nextHour) {
                val hour = nextHour / LongRun.HOUR_MS
                println("%4d %6d %11d %17.1f %15.1f".format(hour, end, bytes, written / MB, nanos / 1e9))
                nextHour += LongRun.HOUR_MS
            }
        }
        println("windows=$windows written=${"%.1f".format(written / MB)} MB encode=${"%.1f".format(nanos / 1e9)} s")
    }

    /** checkpoint.json and words.log as version 2 writes them after each window of a 10 h run. */
    @Test fun checkpointAndWordLogWritesOfATenHourRun() {
        measuring()
        val duration = HOURS * LongRun.HOUR_MS
        val all = run.words(duration)
        var end = 0
        var written = 0L
        var logBytes = 0L
        var nanos = 0L
        var ref = WordLogRef.EMPTY
        println("checkpoint.json with words.log (version 2)")
        println("hour  words  file_bytes  log_MB  written_total_MB  encode_total_s")
        var nextHour = LongRun.HOUR_MS
        for (doneMs in run.windowEnds(duration)) {
            while (end < all.size && all[end].endMs <= doneMs) end++
            val words = all.subList(0, end)
            val state = run.checkpoint(doneMs, duration, words)
            val started = System.nanoTime()
            val settled = WordLog.settled(words, state.recognition.chunks.start * 1000 / LongRun.RATE, ref.count)
            val lines = WordLog.lines(words, ref.count, settled)
            logBytes += lines.size
            if (settled > 0) ref = WordLogRef(settled, logBytes)
            val bytes = CheckpointState.encode(state, ref).toString().toByteArray(Charsets.UTF_8).size
            nanos += System.nanoTime() - started
            written += bytes + lines.size
            if (doneMs >= nextHour) {
                val hour = nextHour / LongRun.HOUR_MS
                val row = listOf(hour, end, bytes, logBytes / MB, written / MB, nanos / 1e9)
                println("%4d %6d %11d %7.2f %17.1f %15.1f".format(*row.toTypedArray<Any>()))
                nextHour += LongRun.HOUR_MS
            }
        }
        println("written=${"%.1f".format(written / MB)} MB encode=${"%.1f".format(nanos / 1e9)} s")
    }

    /** The word body that the seal writes for a long transcript, against the limits of the format code. */
    @Test fun wordBodyOfLongTranscripts() {
        measuring()
        println("word body (pretty JSON, as Transcript.json writes it)")
        println("hours  words  raw_MB  bytes_per_word  gzip_MB  chunks  encode_s")
        for (hours in listOf(1, 2, 4, 8, 10, 11, 12)) {
            val words = run.words(hours * LongRun.HOUR_MS)
            val started = System.nanoTime()
            val body = Transcript(words).json()
            val raw = body.toByteArray(Charsets.UTF_8).size
            val encoded = if (raw <= CassiniPayload.MAX_RAW) {
                CassiniPayload.encode("CASSINI_TX_X_PAYLOAD_", CassiniPayload.WORD_MIME, body)
            } else {
                null
            }
            val seconds = (System.nanoTime() - started) / 1e9
            val gzip = encoded?.first?.getLong("gzipBytes")?.let { "%7.2f".format(it / MB) } ?: "  limit"
            val chunks = encoded?.first?.getInt("chunkCount") ?: -1
            val row = listOf(hours, words.size, raw / MB, raw.toDouble() / words.size, gzip, chunks, seconds)
            println("%5d %6d %7.2f %15.1f %s %7d %9.2f".format(*row.toTypedArray<Any>()))
        }
        println("MAX_RAW=${CassiniPayload.MAX_RAW / MB} MB")
    }

    private companion object {
        const val HOURS = 10L
        const val MB = 1_048_576.0
    }
}
