package org.cassini.android

import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig

internal data class SpeakerTurn(val startMs: Long, val endMs: Long, val speaker: Int)

/** Offline clustering over the whole recording, after ASR has released its model. */
internal object Diarization {
    fun turns(audio: PcmAudio, segmentationModel: String, embeddingModel: String, speakerCount: Int = -1,
              threshold: Float = 0.5f, threads: Int = 2, progress: (Int, Int) -> Unit = { _, _ -> }): List<SpeakerTurn> {
        require(speakerCount == -1 || speakerCount in 1..8)
        require(threshold.isFinite() && threshold > 0 && threads > 0)
        requireUser(audio.samples.isNotEmpty() && audio.samples.all { it.isFinite() }, Failure.AUDIO)
        NativeInference.acquire()
        try {
            requireUser(!Thread.currentThread().isInterrupted, Failure.CANCELLED)
            val samples = if (audio.sampleRate == 16000) audio.samples else resampleTo16k(audio.samples, audio.sampleRate)
            val diarizer = OfflineSpeakerDiarization(config = OfflineSpeakerDiarizationConfig(
                segmentation = OfflineSpeakerSegmentationModelConfig(
                    pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = segmentationModel),
                    numThreads = threads, provider = "cpu"),
                embedding = SpeakerEmbeddingExtractorConfig(model = embeddingModel, numThreads = threads, provider = "cpu"),
                clustering = FastClusteringConfig(numClusters = speakerCount, threshold = threshold),
                minDurationOn = 0.2f, minDurationOff = 0.5f,
            ))
            try {
                // Upstream reports embedding chunks, after segmentation; its return value does not
                // cancel computation. Never throw across JNI. Check interruption after it returns.
                val result = diarizer.processWithCallback(samples, { done, total, _ ->
                    if (!Thread.currentThread().isInterrupted) progress(done, total)
                    0
                })
                requireUser(!Thread.currentThread().isInterrupted, Failure.CANCELLED)
                return result.filter { it.start.isFinite() && it.end.isFinite() && it.speaker >= 0 }.map {
                    SpeakerTurn((it.start * 1000).toLong().coerceIn(0, audio.durationMs),
                        (it.end * 1000).toLong().coerceIn(0, audio.durationMs), it.speaker)
                }.filter { it.endMs > it.startMs }
            } finally { diarizer.release() }
        } finally { NativeInference.lease.release() }
    }

    /** Keep word order, text and timings. Number anonymous speakers in order of first spoken word. */
    fun assign(words: List<Word>, turns: List<SpeakerTurn>, prefix: String = "spk_"): List<Word> {
        // Union duplicate/overlapping intervals for a speaker before counting coverage. Sort to
        // make ties independent of native result order; invalid turns cannot win a nearest match.
        val valid = turns.filter { it.startMs >= 0 && it.endMs > it.startMs && it.speaker >= 0 }
        val union = valid.groupBy { it.speaker }.flatMap { (speaker, intervals) ->
            val merged = mutableListOf<SpeakerTurn>()
            for (turn in intervals.sortedWith(compareBy({ it.startMs }, { it.endMs }))) {
                val previous = merged.lastOrNull()
                if (previous != null && turn.startMs <= previous.endMs) {
                    merged[merged.lastIndex] = previous.copy(endMs = maxOf(previous.endMs, turn.endMs))
                } else merged.add(turn.copy(speaker = speaker))
            }
            merged
        }.sortedWith(compareBy({ it.startMs }, { it.endMs }, { it.speaker }))
        if (union.isEmpty()) return words
        val ids = LinkedHashMap<Int, String>()
        return words.map { word ->
            val overlaps = LinkedHashMap<Int, Long>()
            for (turn in union) {
                val overlap = minOf(word.endMs, turn.endMs) - maxOf(word.startMs, turn.startMs)
                if (overlap > 0) overlaps[turn.speaker] = (overlaps[turn.speaker] ?: 0) + overlap
            }
            val speaker = overlaps.maxByOrNull { it.value }?.key ?: union.minBy { gap(word, it) }.speaker
            word.copy(speaker = ids.getOrPut(speaker) { "$prefix${ids.size + 1}" })
        }
    }

    private fun gap(word: Word, turn: SpeakerTurn) = maxOf(turn.startMs - word.endMs, word.startMs - turn.endMs, 0L)
}
