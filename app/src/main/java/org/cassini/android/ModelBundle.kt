package org.cassini.android

import java.io.File

/** Everything one consented download installs: speech recognition, speaker turns and voiceprints. */
internal class ModelBundle(val parakeet: ModelStore, val speakers: DiarizationModels, val voiceprint: VoiceprintModel) {
    companion object {
        fun inFiles(files: File) = ModelBundle(ModelStore(File(files, "parakeet-v3")),
            DiarizationModels.inFiles(files), VoiceprintModel.inFiles(files))
        /** Bytes fetched over the network for each part, which is what the consent dialog quotes. */
        val parakeetBytes = ModelStore.int8Artifacts.sumOf { it.size }
        val speakerBytes = DiarizationModels.download.size
        val voiceprintBytes = VoiceprintModel.model.size
        val totalBytes = parakeetBytes + speakerBytes + voiceprintBytes
        /** Whole MiB, rounded to tens from 100 MiB up, as `models_size` is written. */
        fun mebibytes(bytes: Long): Long = (bytes / 1048576.0).let { if (it < 100) maxOf(1, Math.round(it)) else Math.round(it / 10) * 10 }
    }
    fun ready() = parakeet.ready() && speakers.ready() && voiceprint.ready()
    fun missingBytes() = (if (parakeet.ready()) 0 else parakeetBytes) +
        (if (speakers.ready()) 0 else speakerBytes) + (if (voiceprint.ready()) 0 else voiceprintBytes)
}
