package org.cassini.core.checkpoint

import org.cassini.core.format.CassiniPayload
import org.cassini.core.format.Transcript
import org.json.JSONObject
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

    /**
     * The whole checkpoint path of each window of a 10 h run, as the job runs it: the state of the recognizer and of
     * the diarizer, the word log, the encoding of checkpoint.json and its text. Three variants:
     *
     * - VERSION_1: 0.1.0 and the first 0.2 builds. The recognizer gives its state as JSON (every word as a pretty
     *   word body, parsed again), and checkpoint.json holds every word.
     * - LOG_JSON_RECOGNIZER: checkpoint.json version 2 with `words.log`, but the recognizer state still as JSON.
     * - LOG_TYPED: version 2, and the recognizer gives its typed state (the job today).
     *
     * The diarizer state goes through its JSON in each variant, as `SortformerStream.checkpoint` gives it.
     */
    @Test fun checkpointPathOfATenHourRun() {
        measuring()
        Variant.entries.forEach(::measure)
    }

    private enum class Variant { VERSION_1, LOG_JSON_RECOGNIZER, LOG_TYPED }

    private fun measure(variant: Variant) {
        val duration = HOURS * LongRun.HOUR_MS
        val all = run.words(duration)
        var end = 0
        var written = 0L
        var logBytes = 0L
        var nanos = 0L
        var ref = WordLogRef.EMPTY
        println("$variant")
        println("hour  words  file_bytes  log_MB  written_total_MB  path_total_s")
        var nextHour = LongRun.HOUR_MS
        for (doneMs in run.windowEnds(duration)) {
            while (end < all.size && all[end].endMs <= doneMs) end++
            val typed = run.checkpoint(doneMs, duration, all.subList(0, end))
            val started = System.nanoTime()
            val recognition = if (variant == Variant.LOG_TYPED) {
                typed.recognition
            } else {
                CheckpointState.recognition(JSONObject(CheckpointState.json(typed.recognition).toString()))
            }
            val diarizer = CheckpointState.diarizer(JSONObject(CheckpointState.json(typed.diarizer!!).toString()))
            val state = typed.copy(recognition = recognition, diarizer = diarizer)
            var lines = ByteArray(0)
            if (variant != Variant.VERSION_1) {
                val words = state.recognition.chunks.words
                val settled = WordLog.settled(words, state.recognition.chunks.start * 1000 / LongRun.RATE, ref.count)
                lines = WordLog.lines(words, ref.count, settled)
                logBytes += lines.size
                if (settled > 0) ref = WordLogRef(settled, logBytes)
            }
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
        println("$variant: written=${"%.1f".format(written / MB)} MB path=${"%.1f".format(nanos / 1e9)} s")
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
