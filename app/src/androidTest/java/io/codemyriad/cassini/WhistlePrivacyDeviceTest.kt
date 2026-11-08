package io.codemyriad.cassini

import android.system.Os
import io.codemyriad.cassini.engine.whistle.WhistleNative
import io.codemyriad.cassini.test.CiSmoke
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/** Bootstrap refuses before any neural dlopen, independently of model and architecture availability. */
@CiSmoke
class WhistlePrivacyDeviceTest {
    private val flags = mapOf("NEEDLE_TELEMETRY" to "0", "DO_NOT_TRACK" to "1", "ORT_DISABLE_TELEMETRY" to "1")

    @Test
    fun missingAndWrongPrivacyFlagsFailBeforeAnyModelOrRuntimeAccess() {
        val original = flags.keys.associateWith { Os.getenv(it) }
        WhistleNative.bootstrap()
        try {
            flags.forEach { (name, value) -> Os.setenv(name, value, true) }
            val uidError = assertThrows(IllegalStateException::class.java) {
                WhistleNative.open(
                    "missing-needle",
                    "missing-ort",
                    ByteBuffer.allocateDirect(0),
                    ByteBuffer.allocateDirect(0),
                )
            }
            assertTrue(requireNotNull(uidError.message).contains("isolated UID"))
            for (key in flags.keys) {
                for (value in listOf(null, "wrong")) refuse(key, value)
            }
        } finally {
            original.forEach { (key, value) -> if (value == null) Os.unsetenv(key) else Os.setenv(key, value, true) }
        }
    }

    private fun refuse(key: String, value: String?) {
        flags.forEach { (name, expected) -> Os.setenv(name, expected, true) }
        if (value == null) Os.unsetenv(key) else Os.setenv(key, value, true)
        val error = assertThrows(IllegalStateException::class.java) {
            WhistleNative.open(
                "missing-needle",
                "missing-ort",
                ByteBuffer.allocateDirect(0),
                ByteBuffer.allocateDirect(0),
            )
        }
        assertTrue(requireNotNull(error.message).contains("Privacy guard"))
    }
}
