package org.cassini.android

import org.cassini.android.ui.clock
import org.cassini.core.audio.Limits
import org.junit.Assert.assertEquals
import org.junit.Test

class ClockTest {
    @Test fun clocksShowHoursOnlyFromOneHour() {
        assertEquals("00:00", clock(0))
        assertEquals("02:59", clock(179_999))
        assertEquals("59:59", clock(3_599_000))
        assertEquals("1:00:00", clock(3_600_000))
        assertEquals("2:00:00", clock(Limits.MAX_RECORDING_MS))
    }
}
