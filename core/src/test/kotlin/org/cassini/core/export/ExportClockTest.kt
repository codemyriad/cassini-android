package org.cassini.core.export

import org.junit.Assert.assertEquals
import org.junit.Test

class ExportClockTest {
    private val underAnHour = 23 * 60_000L + 50_000

    @Test fun shortFormBelowOneHour() {
        assertEquals("00:00", ExportClock.clock(0, underAnHour))
        assertEquals("02:41", ExportClock.clock(161_999, underAnHour))
        assertEquals("59:59", ExportClock.clock(3_599_999, 3_599_999))
    }

    @Test fun longFormFromOneHourOfDuration() {
        assertEquals("0:00:00", ExportClock.clock(0, 3_600_000))
        assertEquals("0:01:05", ExportClock.clock(65_000, 3_600_000))
        assertEquals("1:12:05", ExportClock.clock(4_325_999, 4_400_000))
        assertEquals("12:00:00", ExportClock.clock(43_200_000, 43_200_000))
    }

    @Test fun aTimeBeyondTheDurationStillReadsAsHours() {
        assertEquals("1:00:00", ExportClock.clock(3_600_000, underAnHour))
    }

    @Test fun negativeTimesAreZero() {
        assertEquals("00:00", ExportClock.clock(-5, underAnHour))
        assertEquals("00:00:00,000", ExportClock.srt(-5))
    }

    @Test fun subtitleStamps() {
        assertEquals("00:00:00,000", ExportClock.srt(0))
        assertEquals("01:02:03,456", ExportClock.srt(3_723_456))
        assertEquals("01:02:03.456", ExportClock.vtt(3_723_456))
        assertEquals("00:00:59.999", ExportClock.vtt(59_999))
        assertEquals("100:00:00,007", ExportClock.srt(360_000_007))
    }
}
