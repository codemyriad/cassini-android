package org.cassini.core.speakers

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A person the user named, as the running mean of their confirmed voiceprints. Stored only on this phone. */
data class Voice(val id: String, val name: String, val model: String, val mean: FloatArray,
                 val count: Int, val seconds: Double, val createdAt: Long, val updatedAt: Long) {
    fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("model", model).put("dim", mean.size)
        .put("mean", floats(mean)).put("count", count).put("seconds", seconds).put("createdAt", createdAt).put("updatedAt", updatedAt)

    companion object {
        fun fromJson(json: JSONObject): Voice {
            val mean = floats(json.getJSONArray("mean"))
            require(mean.size == json.getInt("dim") && json.getInt("count") > 0)
            return Voice(json.getString("id"), json.getString("name"), json.getString("model"), mean,
                json.getInt("count"), json.getDouble("seconds"), json.getLong("createdAt"), json.getLong("updatedAt"))
        }
    }
}

fun floats(values: FloatArray) = JSONArray().apply { values.forEach { put(it.toDouble()) } }
fun floats(array: JSONArray) = FloatArray(array.length()) { array.getDouble(it).toFloat() }

/**
 * `voices.json` version 1 and the enrolment arithmetic of 0.1.0 `VoiceStore`, without the file. A file whose
 * `version` is not [VERSION] is refused with [UnsupportedVersion], so a newer file is never read as empty and then
 * overwritten.
 */
object VoiceCodec {
    const val VERSION = 1
    const val MAX_COUNT_WEIGHT = 20

    /** The file's `version` is [found]; this codec reads and writes [VERSION] only. */
    class UnsupportedVersion(val found: Int) : IllegalArgumentException("voices.json version $found")

    fun decode(text: String): List<Voice> {
        val json = JSONObject(text)
        val version = json.getInt("version")
        if (version != VERSION) throw UnsupportedVersion(version)
        val voices = json.getJSONArray("voices")
        return (0 until voices.length()).map { Voice.fromJson(voices.getJSONObject(it)) }
    }

    fun encode(voices: List<Voice>): String =
        JSONObject().put("version", VERSION).put("voices", JSONArray(voices.map { it.json() })).toString()

    /** [voices] with a confirmed print added to [voiceId], or a new person when it is null or unknown; and that voice. */
    fun enrol(voices: List<Voice>, voiceId: String?, name: String, model: String, embedding: FloatArray, seconds: Double,
              now: Long): Pair<List<Voice>, Voice> {
        val result = voices.toMutableList()
        val index = result.indexOfFirst { it.id == voiceId }
        val previous = result.getOrNull(index)?.takeIf { it.model == model && it.mean.size == embedding.size }
        val voice = if (previous == null) Voice(result.getOrNull(index)?.id ?: "voice_${UUID.randomUUID()}", name, model,
            requireNotNull(Voiceprints.normalised(embedding)), 1, seconds, result.getOrNull(index)?.createdAt ?: now, now)
        else previous.copy(name = name, mean = requireNotNull(Voiceprints.normalised(FloatArray(embedding.size) {
            previous.mean[it] * minOf(previous.count, MAX_COUNT_WEIGHT) + embedding[it] })),
            count = previous.count + 1, seconds = previous.seconds + seconds, updatedAt = now)
        if (index >= 0) result[index] = voice else result.add(voice)
        return result to voice
    }

    /**
     * [voice] with one wrong enrolment of [embedding] taken back, or null when it cannot be (another dimension). The
     * caller forgets the person instead when [voice] has a single print.
     */
    fun unenrol(voice: Voice, embedding: FloatArray): Voice? {
        // The mean was normalise(previous * w + e) for a unit previous; recover the length of that sum, then subtract e.
        val e = Voiceprints.normalised(embedding)?.takeIf { it.size == voice.mean.size } ?: return null
        val w = minOf(voice.count - 1, MAX_COUNT_WEIGHT).toDouble()
        val dot = Voiceprints.cosine(voice.mean, e).toDouble()
        val length = (dot + kotlin.math.sqrt(maxOf(0.0, dot * dot - 1 + w * w))).toFloat()
        val mean = Voiceprints.normalised(FloatArray(e.size) { voice.mean[it] * length - e[it] }) ?: voice.mean
        return voice.copy(mean = mean, count = voice.count - 1)
    }
}
