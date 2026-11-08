package io.codemyriad.cassini.engine.whistle

/** Independent of the generated APK manifest. Refuse even a self-consistent replacement before neural load. */
internal object WhistleArtifactPins {
    const val WEIGHTS = "libcassini_whistle_weights.so"
    const val MODEL_OFFSET = 16384L
    const val VAD_OFFSET = 16941056L
    private val hashes = mapOf(
        "libcassini_needle.so" to setOf(
            "f067271e71fb8aec52aca4cc41db135f6642d40eec5a32ab69d81fd3e4446a79",
            "0b3cc2dd6da47bd4ebf8f23bf90c1d69a7bf75765bc33878bfb5303ab5fc21fe",
        ),
        "libcassini_whistle_ort.so" to setOf("e40f09d07dc53726b8bfbf48a7907673b8f86718a057655a62790a39874a7302"),
        WEIGHTS to setOf("1dc7d517d825daf0f57dc2eca06b04c5c03d06cefa5612ab8ca9b5abb7edc72b"),
    )

    fun requireHash(name: String, actual: String) {
        check(actual in hashes.getValue(name)) { "Whistle runtime pin mismatch" }
    }
}
