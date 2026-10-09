package org.cassini.android

import android.util.AtomicFile
import org.cassini.core.checkpoint.CheckpointNames
import org.cassini.core.checkpoint.CheckpointState
import org.cassini.core.checkpoint.DiarizerState
import org.cassini.core.checkpoint.EncoderState
import org.cassini.core.checkpoint.RecognitionState
import org.cassini.core.models.ModelCatalog
import org.cassini.core.speakers.PrintWindow
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

    /** The paused job's state, or null when there is none. Throws when it exists but cannot be resumed. */
    fun load(): CheckpointState? {
        if (!metadata.baseFile.exists() && !File(directory, "checkpoint.json.bak").exists()) return null
        val expected = CheckpointState.Expected(
            source,
            signature(),
            ModelCatalog.PARAKEET_REVISION,
            ModelCatalog.diarizationModel.sha256,
            clips.length(),
            audio.length(),
        )
        val json = JSONObject(metadata.openRead().bufferedReader().use { it.readText() })
        CheckpointState.validate(json, expected)
        return CheckpointState.decode(json)
    }

    /** The job's members with this source's identity and the pinned models of this build. */
    @Suppress("LongParameterList") // One parameter per checkpoint member the job owns.
    fun state(
        recognition: RecognitionState,
        encoder: EncoderState?,
        diarizer: DiarizerState?,
        speakerPrefix: String,
        speakerFailure: Boolean,
        clipSamples: Int,
        voiceClips: Map<String, List<PrintWindow>>?,
        positionSamples: Long,
        doneMs: Long,
        durationMs: Long,
        elapsedMs: Long,
    ) = CheckpointState(
        source, signature(), ModelCatalog.PARAKEET_REVISION, ModelCatalog.diarizationModel.sha256, recognition, encoder,
        diarizer, speakerPrefix, speakerFailure, clipSamples, voiceClips, positionSamples, doneMs, durationMs, elapsedMs,
    )

    /** Commits [state] atomically, in the member order 0.1.0 writes. */
    fun save(state: CheckpointState) {
        require(state.source == source && state.version == CheckpointState.VERSION)
        directory.mkdirs()
        write(metadata, CheckpointState.encode(state))
    }
    /** [generation] is VoiceStore's at the time [remembered] was chosen; a later "forget all" invalidates them. */
    fun saveNames(labels: Map<String, String>, merges: Map<String, String>, remembered: Map<String, String> = emptyMap(), automatic: Map<String, String> = emptyMap(), generation: Long = 0) {
        directory.mkdirs()
        write(names, CheckpointNames.encode(CheckpointNames(labels, merges, remembered, automatic, generation)))
    }
    fun rememberGeneration(): Long = readNames()?.generation ?: 0

    fun loadNames(): Pair<Map<String, String>, Map<String, String>> =
        readNames()?.let { it.labels to it.merges } ?: (emptyMap<String, String>() to emptyMap())

    fun nameExtras(): Pair<Map<String, String>, Map<String, String>> =
        readNames()?.let { it.remembered to it.automatic } ?: (emptyMap<String, String>() to emptyMap())

    private fun readNames(): CheckpointNames? {
        if (!names.baseFile.exists() && !File(directory, "names.json.bak").exists()) return null
        return CheckpointNames.decode(JSONObject(names.openRead().bufferedReader().use { it.readText() }))
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
