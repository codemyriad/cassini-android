package org.cassini.android

import org.json.JSONObject
import java.util.UUID

/** Derive labels without changing the ASR text, word clock, or an older variant's speaker roster. */
internal object SpeakerAttribution {
    data class Result(val transcript: Transcript, val processing: JSONObject)
    fun derive(source: Transcript, turns: List<SpeakerTurn>, previousProcessing: JSONObject?, sourceId: String?,
               speakerCount: Int, elapsedMs: Long, previousAttribution: JSONObject? = null): Result {
        requireUser(turns.isNotEmpty(), Failure.SPEAKERS)
        val words = Diarization.assign(source.words, turns, "diar_${UUID.randomUUID().toString().replace('-', '_')}_")
        requireUser(words.isNotEmpty() && words != source.words, Failure.SPEAKERS)
        val processing = previousProcessing?.let { JSONObject(it.toString()) } ?: JSONObject().put("source", "existing transcript")
        previousAttribution?.let { processing.put("x-sourceAttribution", JSONObject(it.toString())) }
        processing.put("x-derivedFromTranscript", sourceId)
        processing.put("x-speakerDiarization", JSONObject()
            .put("backend", "sherpa-onnx 1.13.7, CPU, 2 threads")
            .put("segmentationModel", "pyannote/segmentation-3.0 INT8")
            .put("segmentationRevision", DiarizationModels.SEGMENTATION_REVISION)
            .put("segmentationSha256", DiarizationModels.segmentation.sha256)
            .put("embeddingModel", "3D-Speaker ERes2Net base zh-cn")
            .put("embeddingSha256", DiarizationModels.embedding.sha256)
            .put("requestedSpeakers", speakerCount).put("threshold", 0.5)
            .put("minDurationOn", 0.2).put("minDurationOff", 0.5)
            .put("elapsedMs", elapsedMs).put("turnCount", turns.size)
            .put("speakerCount", words.map { it.speaker }.distinct().size)
            .put("assignment", "largest union overlap; nearest turn in gaps; one speaker per word")
            .put("sourceSeparation", false))
        return Result(source.copy(words = words), processing)
    }
}
