package org.cassini.android

import org.junit.Assert.*
import org.junit.Test

class ModelPolicyTest {
    private val gib = 1024L * 1024 * 1024
    private val capable = ModelPolicy.Resources(8 * gib, 4 * gib, false, true, 5 * gib, 3 * gib)
    @Test fun fullPrecisionRequiresHardwareAndResourceHeadroom() {
        assertTrue(ModelPolicy.preferFp32(capable))
        assertFalse(ModelPolicy.preferFp32(capable.copy(totalRam = 6 * gib)))
        assertFalse(ModelPolicy.preferFp32(capable.copy(availableRam = 2 * gib)))
        assertFalse(ModelPolicy.preferFp32(capable.copy(lowMemory = true)))
        assertFalse(ModelPolicy.preferFp32(capable.copy(is64Bit = false)))
        assertFalse(ModelPolicy.preferFp32(capable.copy(freeStorage = 3 * gib)))
        assertTrue(ModelPolicy.preferFp32(capable.copy(freeStorage = gib, fp32DownloadBytes = 0)))
    }
    @Test fun realtimeSpeedIsAudioOverElapsedTime() {
        assertEquals(2.0, ProcessingSpeed.realtime(60000, 30000)!!, 0.0)
        assertEquals(0.5, ProcessingSpeed.realtime(30000, 60000)!!, 0.0)
        assertNull(ProcessingSpeed.realtime(60000, 0))
        assertNull(ProcessingSpeed.realtime(0, 30000))
    }
    @Test fun timeLeftFollowsThePaceSoFar() {
        assertEquals(20_000L, ProcessingSpeed.remainingMs(60_000, 180_000, 10_000))
        assertEquals(0L, ProcessingSpeed.remainingMs(180_000, 180_000, 30_000))
        assertNull("No audio covered yet", ProcessingSpeed.remainingMs(0, 180_000, 5_000))
        assertNull("Under a second of work is not a pace", ProcessingSpeed.remainingMs(10_000, 180_000, 999))
        assertNull(ProcessingSpeed.remainingMs(200_000, 180_000, 5_000))
    }
}
