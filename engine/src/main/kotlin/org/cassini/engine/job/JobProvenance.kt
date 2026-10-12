package org.cassini.engine.job

import org.cassini.core.format.SpeakerAttribution
import org.cassini.core.format.SpeakerMerges
import org.cassini.core.format.Word
import org.cassini.core.models.ModelCatalog
import org.cassini.core.speakers.Match
import org.cassini.core.speakers.SpeakerTurn
import org.cassini.core.speakers.Voiceprints
import org.json.JSONObject

/**
 * The `provenance.speechToText.<variant>` record of a transcription, with the keys and values that 0.1.x
 * `MainActivity.transcribe()` wrote after commit ed73e73 (no fixed "it" language). The key order is not part of the
 * contract and the golden test does not check it. Machine-readable, independent of the UI language. The keys `x-inferenceMs`,
 * `x-segmentation`, `x-resumedFromMs`, `x-speakerDiarization`, `x-incrementalDiarization`, `x-speakerMerges` and
 * `x-speakerIdentification` are a contract with the desktop pipeline and the Room cache: never rename them.
 */
object JobProvenance {
    /** The voiceprint model's SHA-256, the `model` of every print and saved voice. */
    val PRINT_MODEL: String = ModelCatalog.voiceprintModel.sha256

    /** The recognition record: the engine, then `x-inferenceMs`, `x-segmentation` and `x-resumedFromMs`. */
    fun recognition(language: String, inferenceMs: Long, segmentation: String, resumedFromMs: Long): JSONObject =
        processing().put("language", language)
            .put("x-inferenceMs", inferenceMs).put("x-segmentation", segmentation)
            .put("x-resumedFromMs", resumedFromMs)

    /** [recognition] plus `x-speakerDiarization` (SpeakerAttribution.record) and `x-incrementalDiarization`. */
    fun diarization(
        words: List<Word>,
        turns: List<SpeakerTurn>,
        recognition: JSONObject,
        diarizationMs: Long,
    ): JSONObject =
        SpeakerAttribution.record(words, turns, recognition, null, diarizationMs)
            .put("x-incrementalDiarization", INCREMENTAL_DIARIZATION)

    /**
     * `x-speakerIdentification`: counts only, so which person a voice resembles never enters the document. [prints]
     * are the speakers with a print and the print length, [matches] their raw matches (note-local voices included)
     * and [names] the voice names the job matched against.
     */
    fun identification(prints: Map<String, Int>, matches: Map<String, Match>, names: Map<String, String>): JSONObject {
        val thresholds = Voiceprints.THRESHOLDS.getValue(PRINT_MODEL)
        return JSONObject()
            .put("model", PRINT_MODEL_NAME).put("sha256", PRINT_MODEL)
            .put("dim", prints.values.firstOrNull() ?: ModelCatalog.VOICEPRINT_DIM)
            .put("metric", "cosine").put("autoThreshold", thresholds.auto.toDouble())
            .put("suggestThreshold", thresholds.suggest.toDouble()).put("margin", thresholds.margin.toDouble())
            .put("speakersWithPrints", prints.size)
            .put("autoApplied", prints.keys.count { automatic(matches[it], names) })
            .put("suggested", prints.keys.count { matches[it]?.state == Match.State.SUGGESTED })
    }

    /** The record sealed into the document: [processing] with `x-speakerMerges` when the user merged speakers. */
    fun sealed(processing: JSONObject, merges: Map<String, String>): JSONObject =
        if (merges.isEmpty()) processing else SpeakerMerges.processing(processing, merges)

    private fun automatic(match: Match?, names: Map<String, String>) =
        match?.state == Match.State.AUTO && match.voiceId in names

    private fun processing(): JSONObject = JSONObject()
        .put("backend", "sherpa-onnx").put("engine", "Parakeet TDT")
        .put("model", "${ModelCatalog.PARAKEET_MODEL_ID} ${ModelCatalog.PARAKEET_PRECISION}")
        .put("device", "Android CPU")
        .put("source", "recording")
        .put("version", "sherpa-onnx 1.13.7; model revision ${ModelCatalog.PARAKEET_REVISION}")

    private const val PRINT_MODEL_NAME = "3dspeaker_speech_campplus_sv_en_voxceleb_16k"
    private const val INCREMENTAL_DIARIZATION = "28 s windows with 2 s overlap, persistent Sortformer speaker cache"
}
