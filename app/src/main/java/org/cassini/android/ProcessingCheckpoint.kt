package org.cassini.android

import android.util.AtomicFile
import org.cassini.core.checkpoint.CheckpointData
import org.cassini.core.checkpoint.CheckpointNames
import org.cassini.core.checkpoint.CheckpointState
import org.cassini.core.models.ModelCatalog
import org.json.JSONObject
import java.io.File

/** One durable job per immutable source. Audio and clips append; metadata commits their safe lengths atomically. */
internal class ProcessingCheckpoint(files: File, private val source: String) {
    private val key = CheckpointState.key(source)
    val directory = File(files, "processing-checkpoints/$key")
    val audio = File(directory, "audio.opus.part")
    val clips = File(directory, "clips.pcm")
    private val metadata get() = AtomicFile(File(directory, "checkpoint.json"))
    private val names get() = AtomicFile(File(directory, "names.json"))
    private fun signature(): String {
        val file = android.net.Uri.parse(source).takeIf { it.scheme == "file" }?.path?.let(::File)
        return CheckpointState.signature(file)
    }
    fun load(): JSONObject? {
        if (!metadata.baseFile.exists() && !File(directory, "checkpoint.json.bak").exists()) return null
        val expected = CheckpointState.Expected(
            source,
            signature(),
            ModelCatalog.PARAKEET_REVISION,
            ModelCatalog.diarizationModel.sha256,
            clips.length(),
            audio.length(),
        )
        return JSONObject(metadata.openRead().bufferedReader().use { it.readText() }).also { CheckpointState.validate(it, expected) }
    }
    fun save(state: JSONObject) {
        directory.mkdirs()
        state.put("version", 1).put("source", source).put("signature", signature())
            .put("model", ModelCatalog.PARAKEET_REVISION).put("speakers", ModelCatalog.diarizationModel.sha256)
        write(metadata, state)
    }
    /** [generation] is VoiceStore's at the time [remembered] was chosen; a later "forget all" invalidates them. */
    fun saveNames(labels: Map<String, String>, merges: Map<String, String>, remembered: Map<String, String> = emptyMap(), automatic: Map<String, String> = emptyMap(), generation: Long = 0) {
        directory.mkdirs()
        write(names, CheckpointNames.encode(CheckpointNames(labels, merges, remembered, automatic, generation)))
    }
    fun rememberGeneration(): Long {
        if (!names.baseFile.exists() && !File(directory, "names.json.bak").exists()) return 0
        return JSONObject(names.openRead().bufferedReader().use { it.readText() }).optLong("generation", 0)
    }
    fun loadNames(): Pair<Map<String, String>, Map<String, String>> {
        if (!names.baseFile.exists() && !File(directory, "names.json.bak").exists()) return emptyMap<String, String>() to emptyMap()
        val json = CheckpointNames.decode(JSONObject(names.openRead().bufferedReader().use { it.readText() }))
        return json.labels to json.merges
    }
    fun nameExtras(): Pair<Map<String, String>, Map<String, String>> {
        if (!names.baseFile.exists() && !File(directory, "names.json.bak").exists()) return emptyMap<String, String>() to emptyMap()
        val json = JSONObject(names.openRead().bufferedReader().use { it.readText() })
        return CheckpointData.strings(json.optJSONObject("remembered") ?: JSONObject()) to
            CheckpointData.strings(json.optJSONObject("automatic") ?: JSONObject())
    }
    fun discard() { directory.deleteRecursively() }
    /** One job per source in this process: viewers in other tasks share the directory. Release in a finally. */
    fun claim(): Boolean = active.add(key)
    fun release() { active.remove(key) }
    private fun write(file: AtomicFile, json: JSONObject) {
        val output = file.startWrite()
        try { output.write(json.toString().toByteArray()); file.finishWrite(output) }
        catch (error: Throwable) { file.failWrite(output); throw error }
    }
    private companion object { val active: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet() }
}
