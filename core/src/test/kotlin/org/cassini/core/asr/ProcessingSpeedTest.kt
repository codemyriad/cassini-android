package org.cassini.core.asr

import org.junit.Assert.*
import org.junit.Test

class ProcessingSpeedTest {
    @Test fun realtimeSpeedIsAudioOverElapsedTime() {
        assertEquals(2.0, ProcessingSpeed.realtime(60000, 30000)!!, 0.0)
        assertEquals(0.5, ProcessingSpeed.realtime(30000, 60000)!!, 0.0)
        assertNull(ProcessingSpeed.realtime(60000, 0))
        assertNull(ProcessingSpeed.realtime(0, 30000))
    }
}
