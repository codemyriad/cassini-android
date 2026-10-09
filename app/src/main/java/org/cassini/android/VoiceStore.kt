package org.cassini.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

/** A person the user named, as the running mean of their confirmed voiceprints. Stored only on this phone. */
internal data class Voice(val id: String, val name: String, val model: String, val mean: FloatArray,
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

internal fun floats(values: FloatArray) = JSONArray().apply { values.forEach { put(it.toDouble()) } }
internal fun floats(array: JSONArray) = FloatArray(array.length()) { array.getDouble(it).toFloat() }

/** Writes a whole file through a synced temporary, so a crash leaves the old or the new content. */
internal fun writeAtomically(file: File, text: String) {
    file.parentFile?.mkdirs()
    val temporary = File(file.parentFile, "${file.name}.tmp")
    temporary.outputStream().use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
    if (!temporary.renameTo(file)) { temporary.delete(); throw IOException("Could not replace $file") }
}

/** filesDir/voices.json. One instance per process; the worker reads while the main thread writes. */
internal class VoiceStore(filesDir: File, private val warn: (String, Throwable) -> Unit = { m, e -> android.util.Log.w("Cassini", m, e) }) {
    private val file = File(filesDir, "voices.json")
    private val generationFile = File(filesDir, "voices.generation")

    /** Advances on "forget all", so remember choices made before it are not enrolled after it. */
    @Synchronized fun generation(): Long = try { if (generationFile.exists()) generationFile.readText().trim().toLong() else 0 }
        catch (error: Exception) { warn("Voice generation unreadable", error); -1 }

    @Synchronized fun load(): List<Voice> = try {
        if (!file.exists()) emptyList() else {
            val json = JSONObject(file.readText())
            require(json.getInt("version") == VERSION)
            val voices = json.getJSONArray("voices")
            (0 until voices.length()).map { Voice.fromJson(voices.getJSONObject(it)) }
        }
    } catch (error: Exception) { warn("Saved voices unreadable", error); emptyList() }

    /** Adds a confirmed print to [voiceId], or creates a person when it is null or unknown. */
    @Synchronized fun enrol(voiceId: String?, name: String, model: String, embedding: FloatArray, seconds: Double, now: Long): Voice {
        val voices = load().toMutableList()
        val index = voices.indexOfFirst { it.id == voiceId }
        val previous = voices.getOrNull(index)?.takeIf { it.model == model && it.mean.size == embedding.size }
        val voice = if (previous == null) Voice(voices.getOrNull(index)?.id ?: "voice_${UUID.randomUUID()}", name, model,
            requireNotNull(Voiceprints.normalised(embedding)), 1, seconds, voices.getOrNull(index)?.createdAt ?: now, now)
        else previous.copy(name = name, mean = requireNotNull(Voiceprints.normalised(FloatArray(embedding.size) {
            previous.mean[it] * minOf(previous.count, MAX_COUNT_WEIGHT) + embedding[it] })),
            count = previous.count + 1, seconds = previous.seconds + seconds, updatedAt = now)
        if (index >= 0) voices[index] = voice else voices.add(voice)
        save(voices)
        return voice
    }

    @Synchronized fun rename(voiceId: String, name: String): Boolean {
        val voices = load()
        if (voices.none { it.id == voiceId }) return false
        save(voices.map { if (it.id == voiceId) it.copy(name = name) else it }); return true
    }

    @Synchronized fun forget(voiceId: String): Boolean {
        val voices = load()
        if (voices.none { it.id == voiceId }) return false
        save(voices.filter { it.id != voiceId }); return true
    }

    @Synchronized fun clear() { writeAtomically(generationFile, (maxOf(generation(), 0) + 1).toString()); file.delete() }

    /** Undoes one wrong enrolment; the last one forgets the person and returns true. */
    @Synchronized fun unenrol(voiceId: String, embedding: FloatArray): Boolean {
        val voices = load()
        val voice = voices.firstOrNull { it.id == voiceId } ?: return false
        if (voice.count <= 1) return forget(voiceId)
        // The mean was normalise(previous * w + e) for a unit previous; recover the length of that sum, then subtract e.
        val e = Voiceprints.normalised(embedding)?.takeIf { it.size == voice.mean.size } ?: return false
        val w = minOf(voice.count - 1, MAX_COUNT_WEIGHT).toDouble()
        val dot = Voiceprints.cosine(voice.mean, e).toDouble()
        val length = (dot + kotlin.math.sqrt(maxOf(0.0, dot * dot - 1 + w * w))).toFloat()
        val mean = Voiceprints.normalised(FloatArray(e.size) { voice.mean[it] * length - e[it] }) ?: voice.mean
        save(voices.map { if (it.id == voiceId) it.copy(mean = mean, count = voice.count - 1) else it })
        return false
    }

    private fun save(voices: List<Voice>) =
        writeAtomically(file, JSONObject().put("version", VERSION).put("voices", JSONArray(voices.map { it.json() })).toString())

    companion object { const val VERSION = 1; const val MAX_COUNT_WEIGHT = 20 }
}
