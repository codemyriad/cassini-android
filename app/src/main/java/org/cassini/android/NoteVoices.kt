package org.cassini.android

import org.json.JSONObject
import java.io.File

internal data class Match(val voiceId: String, val score: Float, val state: State) {
    enum class State { AUTO, SUGGESTED, CONFIRMED, NONE, REJECTED }
}
internal data class SpeakerPrint(val embedding: FloatArray, val seconds: Double, val match: Match?)

/** filesDir/voiceprints/<meetingId>.json: each note's speaker prints and match state keyed by "<variantId>/<speakerId>", never part of a document. */
internal class NoteVoiceStore(filesDir: File, private val warn: (String, Throwable) -> Unit = { m, e -> android.util.Log.w("Cassini", m, e) }) {
    private val directory = File(filesDir, "voiceprints")

    private fun file(meetingId: String): File {
        require(meetingId.isNotEmpty() && meetingId.all { it.isLetterOrDigit() || it == '_' || it == '-' }) { "Bad meeting id" }
        return File(directory, "$meetingId.json")
    }

    /** One transcript variant's prints by speaker id; diarization reuses ids like spk_1 in every variant. */
    @Synchronized fun load(meetingId: String, variantId: String): Map<String, SpeakerPrint> =
        read(file(meetingId))?.second.orEmpty().filterKeys { it.startsWith(prefix(variantId)) }.mapKeys { it.key.removePrefix(prefix(variantId)) }

    /** Stores a variant's prints, replacing that variant's earlier ones; other variants stay. A print from another model resets the file. */
    @Synchronized fun merge(meetingId: String, variantId: String, model: String, prints: Map<String, SpeakerPrint>) {
        if (prints.isEmpty()) return
        val existing = read(file(meetingId))?.takeIf { it.first == model }?.second.orEmpty().filterKeys { !it.startsWith(prefix(variantId)) }
        write(meetingId, model, existing + prints.mapKeys { prefix(variantId) + it.key })
    }

    @Synchronized fun setMatch(meetingId: String, variantId: String, speakerId: String, match: Match?) {
        val (model, prints) = read(file(meetingId)) ?: return
        val key = prefix(variantId) + speakerId
        val print = prints[key] ?: return
        write(meetingId, model, prints + (key to print.copy(match = match)))
    }

    @Synchronized fun delete(meetingId: String) { file(meetingId).delete() }

    /** Removes a forgotten person's matches from every note. Worker thread: it scans all notes. */
    @Synchronized fun forget(voiceId: String) {
        for (note in directory.listFiles().orEmpty().filter { it.extension == "json" }) {
            val (model, prints) = read(note) ?: continue
            if (prints.values.none { it.match?.voiceId == voiceId }) continue
            write(note.nameWithoutExtension, model, prints.mapValues { (_, p) -> if (p.match?.voiceId == voiceId) p.copy(match = null) else p })
        }
    }

    /** How many notes carry an applied (automatic or confirmed) match for each person. Worker thread: it scans all notes. */
    @Synchronized fun noteCounts(): Map<String, Int> = directory.listFiles().orEmpty().filter { it.extension == "json" }
        .flatMap { note -> read(note)?.second?.values.orEmpty().mapNotNull { p -> p.match?.takeIf { it.state == Match.State.AUTO || it.state == Match.State.CONFIRMED }?.voiceId }.distinct() }
        .groupingBy { it }.eachCount()

    @Synchronized fun clear() { directory.deleteRecursively() }

    private fun prefix(variantId: String): String { require(variantId.isNotEmpty() && '/' !in variantId) { "Bad variant id" }; return "$variantId/" }

    private fun read(file: File): Pair<String, Map<String, SpeakerPrint>>? = try {
        if (!file.exists()) null else {
            val json = JSONObject(file.readText())
            require(json.getInt("version") == VERSION)
            val speakers = json.getJSONObject("speakers")
            json.getString("model") to speakers.keys().asSequence().associateWith { id ->
                val s = speakers.getJSONObject(id)
                SpeakerPrint(floats(s.getJSONArray("embedding")), s.getDouble("seconds"), s.optJSONObject("match")?.let {
                    Match(it.getString("voiceId"), it.getDouble("score").toFloat(), Match.State.valueOf(it.getString("state").uppercase()))
                })
            }
        }
    } catch (error: Exception) { warn("Note voiceprints unreadable: ${file.name}", error); null }

    private fun write(meetingId: String, model: String, prints: Map<String, SpeakerPrint>) {
        val speakers = JSONObject()
        for ((id, print) in prints) speakers.put(id, JSONObject().put("embedding", floats(print.embedding)).put("seconds", print.seconds).apply {
            print.match?.let { put("match", JSONObject().put("voiceId", it.voiceId).put("score", it.score.toDouble()).put("state", it.state.name.lowercase())) }
        })
        writeAtomically(file(meetingId), JSONObject().put("version", VERSION).put("model", model).put("speakers", speakers).toString())
    }

    companion object { const val VERSION = 2 }
}
