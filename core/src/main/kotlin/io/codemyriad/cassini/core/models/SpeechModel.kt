package io.codemyriad.cassini.core.models

/** Saved user choice and immutable job identity. Unknown job identities must never select another engine. */
enum class SpeechModel(val setting: String, val identity: String) {
    INCLUDED("included", "whistle-needle-93e88ed-ca52876-decoder-v1-silero-6.2.1-gate-v1"),
    PARAKEET("parakeet", ModelCatalog.PARAKEET_REVISION),
    ;

    companion object {
        fun chosen(saved: SpeechModel?, includedAvailable: Boolean): SpeechModel = when {
            saved == PARAKEET -> PARAKEET
            includedAvailable -> INCLUDED
            else -> PARAKEET
        }

        fun setting(value: String?): SpeechModel? = entries.firstOrNull { it.setting == value }

        fun identity(value: String): SpeechModel = requireNotNull(entries.firstOrNull { it.identity == value }) {
            "Unsupported persisted recognizer identity"
        }
    }
}
