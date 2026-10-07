package org.cassini.android

import org.junit.Assert.*
import org.junit.Test

class ProcessingSpeedTest {
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
