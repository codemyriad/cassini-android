package org.cassini.core.models

/**
 * The three things a model download installs, as the Model download screen lists them. A part is ready when its
 * [primary] model is ready; [extras] are best-effort models fetched with it (Silero VAD with speech recognition)
 * whose absence never makes the part missing. Names may be stored (settings, job rows): never rename them.
 */
enum class ModelPart(val primary: ModelDescriptor, val extras: List<ModelDescriptor> = emptyList()) {
    /** Parakeet (required for any transcription), plus the speech detector. */
    SPEECH(ModelCatalog.PARAKEET, listOf(ModelCatalog.VAD)),

    /** The Nemotron diarizer. Without it every word is one voice. */
    SPEAKERS(ModelCatalog.DIARIZATION),

    /** The CAM++ voiceprint model. Without it no saved voice is recognised. */
    VOICES(ModelCatalog.VOICEPRINT),
    ;

    /** Every model of this part, the primary first. */
    val models: List<ModelDescriptor> get() = listOf(primary) + extras
}
