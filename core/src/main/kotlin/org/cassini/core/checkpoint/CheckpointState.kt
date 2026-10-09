package org.cassini.core.checkpoint

import org.cassini.core.asr.IncrementalState
import org.cassini.core.asr.IncrementalTranscript
import org.cassini.core.audio.Limits
import org.cassini.core.format.OggOpus
import org.cassini.core.format.Transcript
import org.cassini.core.speakers.PrintWindow
import org.cassini.core.speakers.SpeakerBudget
import org.cassini.core.speakers.SpeakerTurn
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** `recognition`: decode time so far and the incremental transcript's window. */
data class RecognitionState(val inferenceMs: Long, val chunks: IncrementalState)

/** `encoder`: the Ogg clock, the unencoded tail and the last two encoded frames of the resumable Opus encoder. */
@Suppress("LongParameterList") // One property per checkpoint member.
class EncoderState(
    val history: FloatArray?,
    val frame: FloatArray,
    val samples: Long,
    val pending: Int,
    val length: Long,
    val serial: Int,
    val sequence: Int,
    val granule: Long,
    val packet: ByteArray?,
)

/** `diarizer`: the Sortformer streaming cache, its compute time and the speaker turns so far. */
class DiarizerState(val cache: FloatArray, val elapsedMs: Long, val turns: List<SpeakerTurn>)

/**
 * `processing-checkpoints/<key>/checkpoint.json`, version 1, as 0.1.0 writes it. [encode] writes exactly the members
 * 0.1.0 writes, in its order (Android's org.json keeps insertion order; the JVM jar does not, so JVM tests compare
 * members, not bytes).
 */
data class CheckpointState(
    val source: String,
    val signature: String,
    val model: String,
    val speakers: String,
    val recognition: RecognitionState,
    val encoder: EncoderState?,
    val diarizer: DiarizerState?,
    val speakerPrefix: String,
    val speakerFailure: Boolean,
    val clipSamples: Int,
    val voiceClips: Map<String, List<PrintWindow>>?,
    val positionSamples: Long,
    val doneMs: Long,
    val durationMs: Long,
    val elapsedMs: Long,
    val version: Int = VERSION,
) {
    /** What a checkpoint must match to be resumed: the same source file, models and on-disk lengths. */
    class Expected(
        val source: String,
        val signature: String,
        val modelRevision: String,
        val speakersSha256: String,
        /** Bytes in `clips.pcm`. */
        val clipsBytes: Long,
        /** Bytes in `audio.opus.part`. */
        val audioBytes: Long,
    )

    /** One decode and one encode function per member type. */
    @Suppress("TooManyFunctions")
    companion object {
        const val VERSION = 1
        const val HISTORY_FLOATS = 640
        const val FRAME_FLOATS = 320
        private const val FLOAT_BYTES = 4L
        const val MAX_CLIP_SAMPLES = SpeakerBudget.MAX_SPEAKERS * SpeakerBudget.SECONDS_PER_SPEAKER * Limits.ASR_RATE

        /** Every member 0.1.0 writes, and the only ones [encode] writes. */
        val KEYS = setOf(
            "version", "source", "signature", "model", "speakers", "recognition", "encoder", "diarizer", "speakerPrefix",
            "speakerFailure", "clipSamples", "voiceClips", "positionSamples", "doneMs", "durationMs", "elapsedMs",
        )

        /** The checkpoint directory name: the SHA-256 of the stored source uri string, never re-derived from a file. */
        fun key(source: String): String = OggOpus.hex(MessageDigest.getInstance("SHA-256").digest(source.toByteArray()))

        /** `length:mtime` of the source file; `null:null` when the source is not a file uri. */
        fun signature(file: File?): String = "${file?.length()}:${file?.lastModified()}"

        /**
         * The rules 0.1.0 `ProcessingCheckpoint.load` applies before a checkpoint is resumed. Throws
         * [IllegalArgumentException] (or org.json's exception for a missing member) when one fails.
         */
        fun validate(json: JSONObject, expected: Expected) = validate(
            json.getInt("version"), json.getString("source"), json.getString("signature"), json.getString("model"),
            json.getString(
                "speakers"
            ),
            json.getInt("clipSamples"), json.getLong("positionSamples"), json.getLong("doneMs"),
            json.optJSONObject("encoder")?.getLong("length"), expected,
        )

        fun validate(state: CheckpointState, expected: Expected) = validate(
            state.version, state.source, state.signature, state.model, state.speakers, state.clipSamples, state.positionSamples,
            state.doneMs, state.encoder?.length, expected,
        )

        @Suppress("LongParameterList")
        private fun validate(
            version: Int,
            source: String,
            signature: String,
            model: String,
            speakers: String,
            clipSamples: Int,
            positionSamples: Long,
            doneMs: Long,
            encoderLength: Long?,
            expected: Expected,
        ) {
            require(version == VERSION && source == expected.source && signature == expected.signature)
            require(model == expected.modelRevision && speakers == expected.speakersSha256)
            require(clipSamples in 0..MAX_CLIP_SAMPLES && expected.clipsBytes >= clipSamples * FLOAT_BYTES)
            require(positionSamples in 0..Limits.MAX_SAMPLES && doneMs in 0..Limits.MAX_RECORDING_MS)
            encoderLength?.let { require(expected.audioBytes >= it) }
        }

        fun decode(json: JSONObject) = CheckpointState(
            source = json.getString("source"),
            signature = json.getString("signature"),
            model = json.getString("model"),
            speakers = json.getString("speakers"),
            recognition = recognition(json.getJSONObject("recognition")),
            encoder = json.optJSONObject("encoder")?.let(::encoder),
            diarizer = json.optJSONObject("diarizer")?.let(::diarizer),
            speakerPrefix = json.getString("speakerPrefix"),
            speakerFailure = json.optBoolean("speakerFailure"),
            clipSamples = json.getInt("clipSamples"),
            voiceClips = json.optJSONObject("voiceClips")?.let(::voiceClips),
            positionSamples = json.getLong("positionSamples"),
            doneMs = json.getLong("doneMs"),
            durationMs = json.getLong("durationMs"),
            elapsedMs = json.getLong("elapsedMs"),
            version = json.getInt("version"),
        )

        /** The JSON 0.1.0 writes: the job's members in its order, then the identity members `save` adds. */
        fun encode(state: CheckpointState): JSONObject = JSONObject()
            .put("recognition", json(state.recognition))
            .put("encoder", state.encoder?.let(::json))
            .put("diarizer", state.diarizer?.let(::json))
            .put("speakerPrefix", state.speakerPrefix)
            .put("speakerFailure", state.speakerFailure)
            .put("clipSamples", state.clipSamples)
            .put("voiceClips", state.voiceClips?.let(::json))
            .put("positionSamples", state.positionSamples)
            .put("doneMs", state.doneMs)
            .put("durationMs", state.durationMs)
            .put("elapsedMs", state.elapsedMs)
            .put("version", state.version)
            .put("source", state.source)
            .put("signature", state.signature)
            .put("model", state.model)
            .put("speakers", state.speakers)

        fun recognition(json: JSONObject) = RecognitionState(
            json.optLong("inferenceMs"),
            incremental(json.getJSONObject("chunks"))
        )

        fun json(recognition: RecognitionState): JSONObject =
            JSONObject().put("inferenceMs", recognition.inferenceMs).put("chunks", json(recognition.chunks))

        fun incremental(json: JSONObject) = IncrementalState(
            json.getLong("start"),
            json.getLong("end"),
            CheckpointData.floats(json.getString("window"), IncrementalTranscript.WINDOW_SAMPLES),
            Transcript.fromJson(json.getJSONObject("words").toString()).words,
        )

        fun json(state: IncrementalState): JSONObject = JSONObject().put("start", state.start).put("end", state.end)
            .put("window", CheckpointData.floats(state.window)).put("words", JSONObject(Transcript(state.words).json()))

        fun encoder(json: JSONObject) = EncoderState(
            history = json.optString(
                "history"
            ).takeIf(String::isNotEmpty)?.let { CheckpointData.floats(it, HISTORY_FLOATS) },
            frame = CheckpointData.floats(json.getString("frame"), FRAME_FLOATS),
            samples = json.getLong("samples"),
            pending = json.getInt("pending"),
            length = json.getLong("length"),
            serial = json.getInt("serial"),
            sequence = json.getInt("sequence"),
            granule = json.getLong("granule"),
            packet = json.optString("packet").takeIf(String::isNotEmpty)?.let(CheckpointData::bytes),
        )

        fun json(encoder: EncoderState): JSONObject = JSONObject()
            .put("history", encoder.history?.let(CheckpointData::floats) ?: "")
            .put("frame", CheckpointData.floats(encoder.frame))
            .put("samples", encoder.samples).put("pending", encoder.pending).put("length", encoder.length)
            .put("serial", encoder.serial).put("sequence", encoder.sequence).put("granule", encoder.granule)
            .put("packet", encoder.packet?.let(CheckpointData::bytes) ?: "")

        fun diarizer(json: JSONObject): DiarizerState {
            val turns = json.getJSONArray("turns")
            return DiarizerState(
                CheckpointData.floats(json.getString("cache"), SpeakerBudget.DIARIZER_STATE_FLOATS),
                json.getLong("elapsedMs"),
                (0 until turns.length()).map { i ->
                    val turn = turns.getJSONArray(i)
                    SpeakerTurn(turn.getLong(0), turn.getLong(1), turn.getInt(2))
                },
            )
        }

        fun json(diarizer: DiarizerState): JSONObject = JSONObject().put("cache", CheckpointData.floats(diarizer.cache))
            .put("elapsedMs", diarizer.elapsedMs)
            .put("turns", JSONArray(diarizer.turns.map { JSONArray(listOf(it.startMs, it.endMs, it.speaker)) }))

        fun voiceClips(json: JSONObject): Map<String, List<PrintWindow>> = json.keys().asSequence().associateWith { id ->
            val list = json.getJSONArray(id)
            (0 until list.length()).map { i -> list.getJSONArray(i).let { PrintWindow(it.getInt(0), it.getInt(1)) } }
        }

        fun json(voiceClips: Map<String, List<PrintWindow>>): JSONObject = JSONObject().also { json ->
            voiceClips.forEach { (id, list) ->
                json.put(
                    id,
                    JSONArray(list.map { JSONArray(listOf(it.startSample, it.endSample)) })
                )
            }
        }
    }
}
