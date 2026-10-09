package org.cassini.android

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File
import kotlin.math.sqrt

internal data class PrintWindow(val startSample: Int, val endSample: Int)
internal data class Thresholds(val auto: Float, val suggest: Float, val margin: Float)

/** One voiceprint per document speaker, from its clean single-voice speech. Prints never leave this phone. */
internal object Voiceprints {
    const val MIN_SEGMENT_MS = 1_500L; const val EDGE_TRIM_MS = 200L
    const val MAX_SECONDS_PER_SPEAKER = 45.0; const val MIN_SECONDS_PER_SPEAKER = 4.0
    const val MAX_WINDOW_MS = 10_000L

    /**
     * Measured with VoiceprintCalibrationTest on the phone, CAM++ prints of up to 45 s from ground-truth turns in six
     * private two-to-three-person call recordings (five people, 13 speaker prints) and the two smoke fixtures:
     * - same person, different recordings: 0.40–0.87, median 0.72 (16 pairs);
     * - different people: −0.04–0.70, median 0.37 (62 pairs); the two fixtures 0.28–0.34;
     * - identifying each speaker against people enrolled from the other recordings: the top score is always the
     *   right person, at 0.52–0.87; with margin 0.08, 8 of 13 apply automatically, none wrong;
     * - a stranger (own voice not enrolled) still scores up to 0.70 against someone else, so auto stays above that.
     * Suggest sits under the lowest right identification (0.52); a wrong suggestion costs one tap.
     */
    val THRESHOLDS = mapOf(VoiceprintModel.model.sha256 to Thresholds(auto = 0.72f, suggest = 0.50f, margin = 0.08f))

    fun select(turns: List<SpeakerTurn>, words: List<Word>, sampleRate: Int, totalSamples: Int): Map<String, List<PrintWindow>> {
        require(sampleRate > 0 && totalSamples >= 0)
        val valid = turns.filter { it.endMs > it.startMs && it.speaker >= 0 }
        // The diarizer speaker that most overlaps a document speaker's words is that speaker's own voice.
        val own = words.groupBy { it.speaker }.mapValues { (_, spoken) ->
            valid.groupBy { it.speaker }.mapValues { (_, own) -> spoken.sumOf { w -> own.sumOf { overlap(w.startMs, w.endMs, it) } } }
                .filterValues { it > 0 }.maxByOrNull { it.value }?.key
        }
        val runs = mutableListOf<Triple<String, Long, Long>>()
        for (word in words.filter { it.endMs > it.startMs }) {
            val last = runs.lastOrNull()
            if (last != null && last.first == word.speaker) runs[runs.lastIndex] = Triple(last.first, last.second, maxOf(last.third, word.endMs))
            else runs.add(Triple(word.speaker, word.startMs, word.endMs))
        }
        val totalMs = totalSamples * 1000L / sampleRate
        val result = LinkedHashMap<String, List<PrintWindow>>()
        for ((speaker, spans) in runs.groupBy { it.first }) {
            val others = valid.filter { it.speaker != own[speaker] }
            val segments = spans.flatMap { (_, start, end) -> clean(start, end, others) }.flatMap { (start, end) ->
                val s = start + EDGE_TRIM_MS; val e = end - EDGE_TRIM_MS
                if (e - s < MIN_SEGMENT_MS) emptyList() else {
                    val parts = ((e - s + MAX_WINDOW_MS - 1) / MAX_WINDOW_MS).toInt()
                    (0 until parts).map { s + (e - s) * it / parts to s + (e - s) * (it + 1) / parts }
                }
            }
            // Longest first, alternating between the recording's thirds so the print is not one moment.
            val thirds = (0..2).map { third -> ArrayDeque(segments.filter { third(it, totalMs) == third }
                .sortedWith(compareBy({ it.first - it.second }, { it.first }))) }
            val chosen = mutableListOf<Pair<Long, Long>>()
            var budget = (MAX_SECONDS_PER_SPEAKER * 1000).toLong()
            while (budget > 0 && thirds.any { it.isNotEmpty() }) for (queue in thirds) {
                val (s, e) = queue.removeFirstOrNull() ?: continue
                val end = minOf(e, s + budget)
                if (end - s >= MIN_SEGMENT_MS) { chosen.add(s to end); budget -= end - s }
                if (budget < MIN_SEGMENT_MS) { budget = 0; break }
            }
            if (chosen.sumOf { it.second - it.first } < MIN_SECONDS_PER_SPEAKER * 1000) continue
            val windows = chosen.sortedBy { it.first }.map { (s, e) ->
                PrintWindow((s * sampleRate / 1000).toInt().coerceIn(0, totalSamples), (e * sampleRate / 1000).toInt().coerceIn(0, totalSamples))
            }.filter { it.endSample > it.startSample }
            if (windows.isNotEmpty()) result[speaker] = windows
        }
        return result
    }

    private fun overlap(start: Long, end: Long, turn: SpeakerTurn) = maxOf(0L, minOf(end, turn.endMs) - maxOf(start, turn.startMs))
    private fun third(segment: Pair<Long, Long>, totalMs: Long) =
        if (totalMs <= 0) 0 else ((segment.first + segment.second) / 2 * 3 / totalMs).toInt().coerceIn(0, 2)

    /** The parts of [start, end) where no other speaker is active. */
    private fun clean(start: Long, end: Long, others: List<SpeakerTurn>): List<Pair<Long, Long>> {
        var pieces = listOf(start to end)
        for (turn in others) pieces = pieces.flatMap { (s, e) ->
            if (turn.endMs <= s || turn.startMs >= e) listOf(s to e)
            else listOfNotNull((s to turn.startMs).takeIf { turn.startMs > s }, (turn.endMs to e).takeIf { turn.endMs < e })
        }
        return pieces
    }

    /** L2-normalised print and seconds of speech behind it, per speaker. Native, under the inference lease. */
    fun compute(model: File, audio: PcmAudio, windows: Map<String, List<PrintWindow>>, active: () -> Unit): Map<String, Pair<FloatArray, Double>> {
        return compute(model, audio.source(), windows, active = active)
    }

    fun compute(model: File, audio: PcmSource, windows: Map<String, List<PrintWindow>>, leaseHeld: Boolean = false, active: () -> Unit): Map<String, Pair<FloatArray, Double>> {
        if (windows.isEmpty()) return emptyMap()
        if (!leaseHeld) NativeInference.acquire()
        try {
            active()
            val extractor = SpeakerEmbeddingExtractor(config = SpeakerEmbeddingExtractorConfig(model = model.absolutePath, numThreads = 2, provider = "cpu"))
            try {
                val prints = LinkedHashMap<String, Pair<FloatArray, Double>>()
                for ((speaker, spans) in windows) {
                    active()
                    val stream = extractor.createStream()
                    try {
                        var samples = 0L
                        for (window in spans) {
                            active()
                            val s = window.startSample.coerceIn(0, audio.sampleCount); val e = window.endSample.coerceIn(s, audio.sampleCount)
                            if (e > s) { stream.acceptWaveform(audio.read(s, e - s), audio.sampleRate); samples += e - s }
                        }
                        stream.inputFinished()
                        if (samples == 0L || !extractor.isReady(stream)) continue
                        normalised(extractor.compute(stream))?.let { prints[speaker] = it to samples.toDouble() / audio.sampleRate }
                    } finally { stream.release() }
                }
                active()
                return prints
            } finally { extractor.release() }
        } finally { if (!leaseHeld) NativeInference.lease.release() }
    }

    fun normalised(vector: FloatArray): FloatArray? {
        val norm = sqrt(vector.sumOf { it.toDouble() * it }).toFloat()
        return if (!norm.isFinite() || norm == 0f) null else FloatArray(vector.size) { vector[it] / norm }
    }

    /** Both prints are L2-normalised, so this is their dot product. */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size)
        var sum = 0.0
        for (i in a.indices) sum += a[i].toDouble() * b[i]
        return sum.toFloat()
    }
}
