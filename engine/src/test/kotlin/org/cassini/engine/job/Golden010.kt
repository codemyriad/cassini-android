// A verbatim copy of 0.1.0 code, kept as it was written: the formatting rules do not apply to it.
@file:Suppress(
    "ArgumentListWrapping",
    "Wrapping",
    "MultiLineIfElse",
    "MaxLineLength",
    "MaximumLineLength",
    "CyclomaticComplexMethod",
    "LongParameterList",
    "LongMethod",
)

package org.cassini.engine.job

import org.cassini.core.format.SpeakerAttribution
import org.cassini.core.format.SpeakerMerges
import org.cassini.core.format.Transcript
import org.cassini.core.models.ModelCatalog
import org.cassini.core.speakers.Match
import org.cassini.core.speakers.SpeakerPrint
import org.cassini.core.speakers.SpeakerTurn
import org.cassini.core.speakers.Voice
import org.cassini.core.speakers.VoiceMatcher
import org.cassini.core.speakers.Voiceprints
import org.json.JSONObject

/**
 * The 0.1.0 `MainActivity.transcribe()` provenance and label code as the golden for the same inputs. It is copied from
 * the 0.1.x line after commit ed73e73, which removed `.put("language", "it")` from `processing()`, so it does not
 * reproduce the v0.1.0 tag byte for byte. Only the free variables became parameters and
 * `getString(R.string.speaker_label, n)` became "Speaker n". The model id, `nvidia/parakeet-tdt-0.6b-v3` in 0.1.0,
 * is the pinned one of this build (Parakeet Ultra). The comparison ignores key order.
 */
object Golden010 {
    private fun processing(precision: String, revision: String? = null): JSONObject = JSONObject()
        .put("backend", "sherpa-onnx").put("engine", "Parakeet TDT")
        .put("model", "${ModelCatalog.PARAKEET_MODEL_ID} $precision")
        .put("device", "Android CPU")
        .put("source", "recording").put("version", "sherpa-onnx 1.13.7${revision?.let { "; model revision $it" }.orEmpty()}")

    fun provenance(
        transcript: Transcript,
        elapsed: Long,
        segmentation: String,
        resumedDoneMs: Long?,
        turns: List<SpeakerTurn>?,
        diarizationMs: Long,
        computed: Map<String, Pair<FloatArray, Double>>,
        knownVoices: List<Voice>,
        voiceprintReady: Boolean,
        merges: Map<String, String>,
    ): JSONObject {
        val draftEdits = emptyMap<String, String>() to merges
        val recognition = processing(ModelCatalog.PARAKEET_PRECISION, ModelCatalog.PARAKEET_REVISION).put("language", transcript.language)
            .put("x-inferenceMs", elapsed).put("x-segmentation", segmentation)
            .put("x-resumedFromMs", resumedDoneMs ?: 0L)
        val attributed = if (turns.isNullOrEmpty() || transcript.words.isEmpty()) null else
            SpeakerAttribution.Result(transcript, SpeakerAttribution.record(transcript.words, turns, recognition, null, diarizationMs)
                .put("x-incrementalDiarization", "28 s windows with 2 s overlap, persistent Sortformer speaker cache"))
        val speakerTurns = turns
        val printModel = ModelCatalog.voiceprintModel.sha256
        val prints: Map<String, SpeakerPrint> = if (speakerTurns == null || attributed == null || !voiceprintReady) emptyMap() else {
            val matches = VoiceMatcher.match(computed.mapValues { it.value.first }, knownVoices, printModel, Voiceprints.THRESHOLDS.getValue(printModel))
            computed.mapValues { (id, print) -> SpeakerPrint(print.first, print.second, matches[id]) }
        }
        val names = knownVoices.associate { it.id to it.name }
        if (attributed != null && speakerTurns != null && voiceprintReady) {
            val t = Voiceprints.THRESHOLDS.getValue(printModel)
            attributed.processing.put("x-speakerIdentification", JSONObject()
                .put("model", "3dspeaker_speech_campplus_sv_en_voxceleb_16k").put("sha256", printModel).put("dim", prints.values.firstOrNull()?.embedding?.size ?: 512)
                .put("metric", "cosine").put("autoThreshold", t.auto.toDouble()).put("suggestThreshold", t.suggest.toDouble()).put("margin", t.margin.toDouble())
                .put("speakersWithPrints", prints.size).put("autoApplied", prints.values.count { it.match?.state == Match.State.AUTO && it.match?.voiceId in names })
                .put("suggested", prints.values.count { it.match?.state == Match.State.SUGGESTED }))
        }
        return (attributed?.processing ?: recognition).let {
            if (draftEdits.second.isEmpty()) it else SpeakerMerges.processing(it, draftEdits.second)
        }
    }

    fun labels(
        transcript: Transcript,
        draftNames: Map<String, String>,
        computed: Map<String, Pair<FloatArray, Double>>,
        knownVoices: List<Voice>,
    ): Map<String, String> {
        val printModel = ModelCatalog.voiceprintModel.sha256
        val matches = VoiceMatcher.match(computed.mapValues { it.value.first }, knownVoices, printModel, Voiceprints.THRESHOLDS.getValue(printModel))
        val prints = computed.mapValues { (id, print) -> SpeakerPrint(print.first, print.second, matches[id]) }
        val names = knownVoices.associate { it.id to it.name }
        return transcript.words.map { it.speaker }.distinct().mapIndexed { index, id ->
            id to (draftNames[id] ?: prints[id]?.match?.takeIf { it.state == Match.State.AUTO }?.let { names[it.voiceId] } ?: "Speaker ${index + 1}")
        }.toMap()
    }
}
