package org.cassini.android

import org.cassini.core.models.ModelCatalog
import java.io.File

/** Everything one consented download installs: speech recognition, speaker turns and voiceprints. */
internal class ModelBundle(val parakeet: ModelStore, val speakers: DiarizationModels, val voiceprint: VoiceprintModel) {
    companion object {
        fun inFiles(files: File) = ModelBundle(ModelStore(ModelCatalog.PARAKEET.directory(files)),
            DiarizationModels.inFiles(files), VoiceprintModel.inFiles(files))
        /** Bytes fetched over the network for each part, which is what the consent dialog quotes. */
        val parakeetBytes = ModelCatalog.parakeetBytes
        val speakerBytes = ModelCatalog.speakerBytes
        val voiceprintBytes = ModelCatalog.voiceprintBytes
        val totalBytes = ModelCatalog.totalBytes
        /** Whole MiB, rounded to tens from 100 MiB up, as `models_size` is written. */
        fun mebibytes(bytes: Long): Long = ModelCatalog.mebibytes(bytes)
    }
    /** Parakeet is fetched only after consent, so once it is here the user has agreed; a missing
     *  optional model is retried by the run itself and must not bring the dialog back or block auto-transcribe. */
    fun consented() = parakeet.ready()
    fun ready() = parakeet.ready() && speakers.ready() && voiceprint.ready()
    fun missingBytes() = (if (parakeet.ready()) 0 else parakeetBytes) +
        (if (speakers.ready()) 0 else speakerBytes) + (if (voiceprint.ready()) 0 else voiceprintBytes)
}
