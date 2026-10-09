package org.cassini.android

import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationSortformerModelConfig
import org.cassini.core.Failure
import org.cassini.core.audio.Limits
import org.cassini.core.audio.PcmAudio
import org.cassini.core.audio.resampleTo16k
import org.cassini.core.checkpoint.CheckpointData
import org.cassini.core.requireUser
import org.cassini.core.speakers.Diarization
import org.cassini.core.speakers.SpeakerBudget
import org.cassini.core.speakers.SpeakerTurn
import org.cassini.core.speakers.TurnHistory

/** Caller owns the inference lease. One instance preserves speaker IDs across chunks. */
internal class SortformerStream(model: String, restored: org.json.JSONObject? = null) : AutoCloseable {
    companion object {
        /** Speaker turns of a whole recording in one native call, under the inference lease. */
        fun turns(audio: PcmAudio, model: String, threads: Int = 2, progress: (Int, Int) -> Unit = { _, _ -> }): List<SpeakerTurn> {
            require(threads > 0)
            requireUser(audio.samples.isNotEmpty(), Failure.AUDIO)
            NativeInference.acquire()
            try {
                requireUser(!Thread.currentThread().isInterrupted, Failure.CANCELLED)
                // The decoder already yields finite 16 kHz samples, so this is the same array.
                val samples = resampleTo16k(audio.samples, audio.sampleRate)
                val diarizer = OfflineSpeakerDiarization(config = config(model, threads))
                try {
                    // The callback reports each 27 s chunk; its return value does not cancel computation.
                    // Never throw across JNI. Check interruption after it returns.
                    // The JNI looks up `invoke(IIJ)Ljava/lang/Integer;`, which only a class-compiled lambda has:
                    // Kotlin 2 compiles plain lambdas through invokedynamic, with only the erased invoke.
                    val result = diarizer.processWithCallback(
                        samples,
                        @JvmSerializableLambda { done, total, _ ->
                            if (!Thread.currentThread().isInterrupted) progress(done, total)
                            0
                        },
                    )
                    requireUser(!Thread.currentThread().isInterrupted, Failure.CANCELLED)
                    return result.filter { it.start.isFinite() && it.end.isFinite() && it.speaker >= 0 }.map {
                        SpeakerTurn((it.start * 1000).toLong().coerceIn(0, audio.durationMs),
                            (it.end * 1000).toLong().coerceIn(0, audio.durationMs), it.speaker)
                    }.filter { it.endMs > it.startMs }
                } finally { diarizer.release() }
            } finally { NativeInference.lease.release() }
        }

        private fun config(model: String, threads: Int) = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                sortformer = OfflineSpeakerSegmentationSortformerModelConfig(model = model, threshold = Diarization.THRESHOLD),
                numThreads = threads, provider = "cpu"),
            minDurationOn = Diarization.MIN_DURATION_ON, minDurationOff = Diarization.MIN_DURATION_OFF,
        )
    }

    private val diarizer = OfflineSpeakerDiarization(config = config(model, 2))
    var elapsedMs = 0L
        private set
    private val history: TurnHistory
    init {
        var restoredTurns = emptyList<SpeakerTurn>()
        restored?.let {
            try {
                require(diarizer.restoreStreamingState(CheckpointData.floats(it.getString("cache"), SpeakerBudget.DIARIZER_STATE_FLOATS)))
                elapsedMs = it.getLong("elapsedMs")
                val turns = it.getJSONArray("turns")
                restoredTurns = (0 until turns.length()).map { i ->
                    val turn = turns.getJSONArray(i)
                    SpeakerTurn(turn.getLong(0), turn.getLong(1), turn.getInt(2))
                }
            } catch (error: Throwable) { diarizer.release(); throw error }
        }
        history = TurnHistory(restoredTurns)
    }
    fun checkpoint() = org.json.JSONObject().put("cache", CheckpointData.floats(diarizer.saveStreamingState()))
        .put("elapsedMs", elapsedMs).put("turns", org.json.JSONArray(history.turns.map { org.json.JSONArray(listOf(it.startMs, it.endMs, it.speaker)) }))
    val turns: List<SpeakerTurn> get() = history.turns

    fun process(audio: PcmAudio, startMs: Long): List<SpeakerTurn> {
        require(audio.sampleRate == Limits.ASR_RATE && audio.durationMs <= 28_000)
        requireUser(!Thread.currentThread().isInterrupted, Failure.CANCELLED)
        val began = System.nanoTime()
        val found = diarizer.processStreamingChunk(audio.samples).filter {
            it.start.isFinite() && it.end.isFinite() && it.speaker in 0 until SpeakerBudget.MAX_SPEAKERS
        }.map {
            SpeakerTurn((it.start * 1000).toLong().coerceIn(0, audio.durationMs),
                (it.end * 1000).toLong().coerceIn(0, audio.durationMs), it.speaker)
        }.filter { it.endMs > it.startMs }
        elapsedMs += (System.nanoTime() - began) / 1_000_000
        requireUser(!Thread.currentThread().isInterrupted, Failure.CANCELLED)
        history.add(found, startMs)
        return found
    }

    override fun close() = diarizer.release()
}
