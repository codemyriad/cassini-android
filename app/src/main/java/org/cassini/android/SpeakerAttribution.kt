package org.cassini.android

import org.json.JSONObject
import java.util.UUID

/** Derive labels without changing the ASR text, word clock, or an older variant's speaker roster. */
internal object SpeakerAttribution {
    data class Result(val transcript: Transcript, val processing: JSONObject)
    fun derive(source: Transcript, turns: List<SpeakerTurn>, previousProcessing: JSONObject?, sourceId: String?,
               elapsedMs: Long, previousAttribution: JSONObject? = null): Result {
        requireUser(turns.isNotEmpty(), Failure.SPEAKERS)
        val words = Diarization.assign(source.words, turns, "diar_${UUID.randomUUID().toString().replace('-', '_')}_")
        requireUser(words.isNotEmpty() && words != source.words, Failure.SPEAKERS)
        val processing = previousProcessing?.let { JSONObject(it.toString()) } ?: JSONObject().put("source", "existing transcript")
        previousAttribution?.let { processing.put("x-sourceAttribution", JSONObject(it.toString())) }
        processing.put("x-derivedFromTranscript", sourceId)
        processing.put("x-speakerDiarization", JSONObject()
            .put("backend", "sherpa-onnx 1.13.7 with Sortformer diarization, CPU, 2 threads")
            .put("model", "nvidia/Nemotron-3-Diarization INT8")
            .put("modelRevision", DiarizationModels.SOURCE_REVISION)
            .put("modelSha256", DiarizationModels.model.sha256)
            .put("threshold", Diarization.THRESHOLD.toDouble())
            .put("minDurationOn", Diarization.MIN_DURATION_ON.toDouble()).put("minDurationOff", Diarization.MIN_DURATION_OFF.toDouble())
            .put("elapsedMs", elapsedMs).put("turnCount", turns.size)
            .put("speakerCount", words.map { it.speaker }.distinct().size)
            .put("assignment", "largest union overlap; nearest turn in gaps; one speaker per word")
            .put("sourceSeparation", false))
        return Result(source.copy(words = words), processing)
    }
}
