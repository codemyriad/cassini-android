package io.codemyriad.cassini.core.models

/** Saved user choice and immutable job identity. Unknown job identities must never select another engine. */
enum class SpeechModel(val setting: String, val identity: String) {
    INCLUDED(
        "included",
        "whistle:needle=93e88ede867e4311c39151285e74df513e4b835b;" +
            "weights=b6e02f048568ac5d01a2042556c658061e699acbc0aa2a1439f52f3d461dffeb;" +
            "vad=1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3;decoder=1;gate=1",
    ),
    PARAKEET("parakeet", ModelCatalog.PARAKEET_REVISION),
    ;

    companion object {
        fun chosen(saved: SpeechModel?, includedAvailable: Boolean, parakeetReady: Boolean = false): SpeechModel = when {
            !includedAvailable -> PARAKEET
            saved == INCLUDED -> INCLUDED
            parakeetReady -> PARAKEET
            else -> INCLUDED
        }

        fun setting(value: String?): SpeechModel? = entries.firstOrNull { it.setting == value }

        fun identity(value: String): SpeechModel = requireNotNull(entries.firstOrNull { it.identity == value }) {
            "Unsupported persisted recognizer identity"
        }
    }
}
