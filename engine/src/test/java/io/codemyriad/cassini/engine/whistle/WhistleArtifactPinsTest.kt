package io.codemyriad.cassini.engine.whistle

import org.junit.Assert.assertThrows
import org.junit.Test

class WhistleArtifactPinsTest {
    @Test
    fun selfConsistentReplacementCannotAuthorizeRuntime() {
        for (name in listOf("libcassini_needle.so", "libcassini_whistle_ort.so", WhistleArtifactPins.WEIGHTS)) {
            assertThrows(IllegalStateException::class.java) { WhistleArtifactPins.requireHash(name, "0".repeat(64)) }
        }
        WhistleArtifactPins.requireHash("libcassini_needle.so", "f067271e71fb8aec52aca4cc41db135f6642d40eec5a32ab69d81fd3e4446a79")
        WhistleArtifactPins.requireHash("libcassini_needle.so", "0b3cc2dd6da47bd4ebf8f23bf90c1d69a7bf75765bc33878bfb5303ab5fc21fe")
    }
}
