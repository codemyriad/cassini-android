package org.cassini.core.speakers

import org.json.JSONObject

data class Match(val voiceId: String, val score: Float, val state: State) {
    enum class State { AUTO, SUGGESTED, CONFIRMED, NONE, REJECTED }
}
data class SpeakerPrint(val embedding: FloatArray, val seconds: Double, val match: Match?)

/**
 * `voiceprints/<meetingId>.json` version 2 of 0.1.0 `NoteVoices`: each note's speaker prints and match state keyed by
 * "<variantId>/<speakerId>", never part of a document. A file whose `version` is not [VERSION] is refused with
 * [UnsupportedVersion] instead of being read as empty, so that a caller can leave a newer file alone. The 0.1.0
 * `NoteVoiceStore` in `:app` does not do that yet: like 0.1.0, it treats any unreadable file as missing and the next
 * merge replaces it. Keeping a newer file read-only arrives with the P2 `:data` repositories.
 */
object NoteVoiceCodec {
    const val VERSION = 2

    /** The file's `version` is [found]; this codec reads and writes [VERSION] only. [newer] files must be kept. */
    class UnsupportedVersion(val found: Int) : IllegalArgumentException("voiceprints version $found") {
        val newer get() = found > VERSION
    }

    /** The file name of [meetingId]'s prints; refuses an id that could leave the directory. */
    fun fileName(meetingId: String): String {
        require(meetingId.isNotEmpty() && meetingId.all { it.isLetterOrDigit() || it == '_' || it == '-' }) { "Bad meeting id" }
        return "$meetingId.json"
    }

    /** The key prefix of one transcript variant's prints. */
    fun prefix(variantId: String): String { require(variantId.isNotEmpty() && '/' !in variantId) { "Bad variant id" }; return "$variantId/" }

    /** The model sha256 the prints were made with, and the prints by "<variantId>/<speakerId>". */
    fun decode(text: String): Pair<String, Map<String, SpeakerPrint>> {
        val json = JSONObject(text)
        val version = json.getInt("version")
        if (version != VERSION) throw UnsupportedVersion(version)
        val speakers = json.getJSONObject("speakers")
        return json.getString("model") to speakers.keys().asSequence().associateWith { id ->
            val s = speakers.getJSONObject(id)
            SpeakerPrint(floats(s.getJSONArray("embedding")), s.getDouble("seconds"), s.optJSONObject("match")?.let {
                Match(it.getString("voiceId"), it.getDouble("score").toFloat(), Match.State.valueOf(it.getString("state").uppercase()))
            })
        }
    }

    fun encode(model: String, prints: Map<String, SpeakerPrint>): String {
        val speakers = JSONObject()
        for ((id, print) in prints) speakers.put(id, JSONObject().put("embedding", floats(print.embedding)).put("seconds", print.seconds).apply {
            print.match?.let { put("match", JSONObject().put("voiceId", it.voiceId).put("score", it.score.toDouble()).put("state", it.state.name.lowercase())) }
        })
        return JSONObject().put("version", VERSION).put("model", model).put("speakers", speakers).toString()
    }
}
