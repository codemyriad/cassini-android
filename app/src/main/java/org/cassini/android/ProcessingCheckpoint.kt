package org.cassini.android

import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal class ProcessingPaused : RuntimeException()

/** One durable job per immutable source. Audio and clips append; metadata commits their safe lengths atomically. */
internal class ProcessingCheckpoint(files: File, private val source: String) {
    private val key = OggOpus.hex(MessageDigest.getInstance("SHA-256").digest(source.toByteArray()))
    val directory = File(files, "processing-checkpoints/$key")
    val audio = File(directory, "audio.opus.part")
    val clips = File(directory, "clips.pcm")
    private val metadata get() = AtomicFile(File(directory, "checkpoint.json"))
    private val names get() = AtomicFile(File(directory, "names.json"))
    private fun signature(): String {
        val file = android.net.Uri.parse(source).takeIf { it.scheme == "file" }?.path?.let(::File)
        return "${file?.length()}:${file?.lastModified()}"
    }
    fun load(): JSONObject? {
        if (!metadata.baseFile.exists() && !File(directory, "checkpoint.json.bak").exists()) return null
        return JSONObject(metadata.openRead().bufferedReader().use { it.readText() }).also {
            require(it.getInt("version") == 1 && it.getString("source") == source && it.getString("signature") == signature())
            require(it.getString("model") == ModelStore.REVISION && it.getString("speakers") == DiarizationModels.model.sha256)
            require(it.getInt("clipSamples") in 0..(8 * 45 * Limits.ASR_RATE) && clips.length() >= it.getInt("clipSamples") * 4L)
            require(it.getLong("positionSamples") in 0..Limits.MAX_SAMPLES && it.getLong("doneMs") in 0..Limits.MAX_RECORDING_MS)
            it.optJSONObject("encoder")?.let { encoder -> require(audio.length() >= encoder.getLong("length")) }
        }
    }
    fun save(state: JSONObject) {
        directory.mkdirs()
        state.put("version", 1).put("source", source).put("signature", signature())
            .put("model", ModelStore.REVISION).put("speakers", DiarizationModels.model.sha256)
        write(metadata, state)
    }
    fun saveNames(labels: Map<String, String>, merges: Map<String, String>, remembered: Map<String, String> = emptyMap(), automatic: Map<String, String> = emptyMap()) {
        directory.mkdirs()
        write(names, JSONObject().put("labels", JSONObject(labels)).put("merges", JSONObject(merges)).put("remembered", JSONObject(remembered)).put("automatic", JSONObject(automatic)))
    }
    fun loadNames(): Pair<Map<String, String>, Map<String, String>> {
        if (!names.baseFile.exists() && !File(directory, "names.json.bak").exists()) return emptyMap<String, String>() to emptyMap()
        val json = JSONObject(names.openRead().bufferedReader().use { it.readText() })
        return CheckpointData.strings(json.getJSONObject("labels")) to CheckpointData.strings(json.getJSONObject("merges"))
    }
    fun nameExtras(): Pair<Map<String, String>, Map<String, String>> {
        if (!names.baseFile.exists() && !File(directory, "names.json.bak").exists()) return emptyMap<String, String>() to emptyMap()
        val json = JSONObject(names.openRead().bufferedReader().use { it.readText() })
        return CheckpointData.strings(json.optJSONObject("remembered") ?: JSONObject()) to
            CheckpointData.strings(json.optJSONObject("automatic") ?: JSONObject())
    }
    fun discard() { directory.deleteRecursively() }
    private fun write(file: AtomicFile, json: JSONObject) {
        val output = file.startWrite()
        try { output.write(json.toString().toByteArray()); file.finishWrite(output) }
        catch (error: Throwable) { file.failWrite(output); throw error }
    }
}
