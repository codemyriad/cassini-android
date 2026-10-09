package org.cassini.android

import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationSortformerModelConfig

internal data class SpeakerTurn(val startMs: Long, val endMs: Long, val speaker: Int)

/** Sortformer speaker activity, with full-recording and stateful chunk APIs. */
internal object Diarization {
    /** Speaker activity above this probability counts as speech. The model decides the number of speakers (up to 8). */
    const val THRESHOLD = 0.5f
    const val MIN_DURATION_ON = 0.3f
    const val MIN_DURATION_OFF = 0.5f

    fun turns(audio: PcmAudio, model: String, threads: Int = 2, progress: (Int, Int) -> Unit = { _, _ -> }): List<SpeakerTurn> {
        require(threads > 0)
        requireUser(audio.samples.isNotEmpty(), Failure.AUDIO)
        NativeInference.acquire()
        try {
            requireUser(!Thread.currentThread().isInterrupted, Failure.CANCELLED)
            // The decoder already yields finite 16 kHz samples, so this is the same array.
            val samples = resampleTo16k(audio.samples, audio.sampleRate)
            val diarizer = OfflineSpeakerDiarization(config = OfflineSpeakerDiarizationConfig(
                segmentation = OfflineSpeakerSegmentationModelConfig(
                    sortformer = OfflineSpeakerSegmentationSortformerModelConfig(model = model, threshold = THRESHOLD),
                    numThreads = threads, provider = "cpu"),
                minDurationOn = MIN_DURATION_ON, minDurationOff = MIN_DURATION_OFF,
            ))
            try {
                // The callback reports each 27 s chunk; its return value does not cancel computation.
                // Never throw across JNI. Check interruption after it returns.
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

    /** Caller owns the inference lease. One instance preserves speaker IDs across chunks. */
    class Streaming(model: String, restored: org.json.JSONObject? = null) : AutoCloseable {
        private val diarizer = OfflineSpeakerDiarization(config = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                sortformer = OfflineSpeakerSegmentationSortformerModelConfig(model = model, threshold = THRESHOLD),
                numThreads = 2, provider = "cpu"),
            minDurationOn = MIN_DURATION_ON, minDurationOff = MIN_DURATION_OFF,
        ))
        var elapsedMs = 0L
            private set
        private val history = mutableListOf<SpeakerTurn>()
        init {
            restored?.let {
                try {
                    require(diarizer.restoreStreamingState(CheckpointData.floats(it.getString("cache"), 200000)))
                    elapsedMs = it.getLong("elapsedMs")
                    val turns = it.getJSONArray("turns")
                    for (i in 0 until turns.length()) {
                        val turn = turns.getJSONArray(i)
                        history += SpeakerTurn(turn.getLong(0), turn.getLong(1), turn.getInt(2))
                    }
                } catch (error: Throwable) { diarizer.release(); throw error }
            }
        }
        fun checkpoint() = org.json.JSONObject().put("cache", CheckpointData.floats(diarizer.saveStreamingState()))
            .put("elapsedMs", elapsedMs).put("turns", org.json.JSONArray(history.map { org.json.JSONArray(listOf(it.startMs, it.endMs, it.speaker)) }))
        val turns: List<SpeakerTurn> get() = history.toList()

        fun process(audio: PcmAudio, startMs: Long): List<SpeakerTurn> {
            require(audio.sampleRate == Limits.ASR_RATE && audio.durationMs <= 28_000)
            requireUser(!Thread.currentThread().isInterrupted, Failure.CANCELLED)
            val began = System.nanoTime()
            val found = diarizer.processStreamingChunk(audio.samples).filter {
                it.start.isFinite() && it.end.isFinite() && it.speaker in 0..7
            }.map {
                SpeakerTurn((it.start * 1000).toLong().coerceIn(0, audio.durationMs),
                    (it.end * 1000).toLong().coerceIn(0, audio.durationMs), it.speaker)
            }.filter { it.endMs > it.startMs }
            elapsedMs += (System.nanoTime() - began) / 1_000_000
            requireUser(!Thread.currentThread().isInterrupted, Failure.CANCELLED)
            val cut = if (startMs == 0L) 0 else startMs + 1000
            for (index in history.indices.reversed()) {
                val old = history[index]
                if (old.startMs >= cut) history.removeAt(index)
                else if (old.endMs > cut) history[index] = old.copy(endMs = cut)
            }
            history.addAll(found.map {
                it.copy(startMs = maxOf(cut, startMs + it.startMs), endMs = startMs + it.endMs)
            }.filter { it.endMs > it.startMs })
            return found
        }

        override fun close() = diarizer.release()
    }

    /** Keep word order, text and timings. Number anonymous speakers in order of first spoken word. */
    fun assign(words: List<Word>, turns: List<SpeakerTurn>, prefix: String = "spk_", stableIds: Boolean = false): List<Word> {
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
            word.copy(speaker = if (stableIds) "$prefix${speaker + 1}" else ids.getOrPut(speaker) { "$prefix${ids.size + 1}" })
        }
    }

    private fun gap(word: Word, turn: SpeakerTurn) = maxOf(turn.startMs - word.endMs, word.startMs - turn.endMs, 0L)
}
