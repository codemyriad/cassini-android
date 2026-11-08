package io.codemyriad.cassini.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WhistleCapabilityTest {
    @Test
    fun `only validated OS and arm64 dot product are eligible`() {
        for (api in 26..35) {
            for (cpu in WhistleCpu.entries) {
                val actual = WhistleCapability.assess(api, cpu)
                if (api >= 29 && cpu == WhistleCpu.ARM64_DOTPROD) {
                    assertEquals("$api $cpu", WhistleCapability.ELIGIBLE, actual)
                } else {
                    assertNotEquals("$api $cpu", WhistleCapability.ELIGIBLE, actual)
                }
            }
        }
    }

    @Test
    fun `old kernel SDOT smoke cannot override the production OS gate`() {
        assertEquals(WhistleCapability.ANDROID_TOO_OLD, WhistleCapability.assess(26, WhistleCpu.ARM64_DOTPROD))
        assertEquals(WhistleCapability.ANDROID_TOO_OLD, WhistleCapability.assess(28, WhistleCpu.ARM64_DOTPROD))
    }

    @Test
    fun `unavailable reasons distinguish missing artifact feature and failed probe`() {
        assertEquals(WhistleCapability.UNSUPPORTED_PROCESS, WhistleCapability.assess(29, WhistleCpu.OTHER_PROCESS))
        assertEquals(WhistleCapability.DOT_PRODUCT_UNAVAILABLE, WhistleCapability.assess(35, WhistleCpu.ARM64_BASELINE))
        assertEquals(WhistleCapability.CPU_PROBE_FAILED, WhistleCapability.assess(35, WhistleCpu.UNKNOWN))
    }
}
