package io.codemyriad.cassini

import android.os.Build
import io.codemyriad.cassini.core.models.WhistleCapability
import io.codemyriad.cassini.core.models.WhistleCpu
import io.codemyriad.cassini.engine.asr.WhistleDeviceCapability
import io.codemyriad.cassini.test.CiSmoke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** No model, Needle runtime, optional instruction execution, audio or network. */
@CiSmoke
class WhistleCapabilityDeviceTest {
    @Test
    fun packagedProbeReportsTheExecutingArchitecture() {
        val cpu = WhistleDeviceCapability.cpu()
        assertNotEquals("Packaged JNI must resolve", WhistleCpu.UNKNOWN, cpu)
        when (System.getProperty("os.arch")) {
            "aarch64" -> assertNotEquals(WhistleCpu.OTHER_PROCESS, cpu)
            "x86_64", "amd64" -> assertEquals(WhistleCpu.OTHER_PROCESS, cpu)
            else -> error("Unexpected test process architecture")
        }
        val capability = WhistleDeviceCapability.current()
        if (Build.VERSION.SDK_INT < 29) {
            assertEquals(WhistleCapability.ANDROID_TOO_OLD, capability)
        } else if (cpu == WhistleCpu.OTHER_PROCESS) {
            assertEquals(WhistleCapability.UNSUPPORTED_PROCESS, capability)
        }
    }
}
