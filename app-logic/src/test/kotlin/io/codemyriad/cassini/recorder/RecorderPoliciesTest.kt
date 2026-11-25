package io.codemyriad.cassini.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class RecorderPoliciesTest {
    private val phone = LocalDateTime.of(2026, 11, 25, 10, 0, 0)

    @Test fun theClockIsSetOnlyBeyondFiveSecondsOrWhenUnreadable() {
        assertFalse(RecorderClock.needsSet(phone, phone))
        assertFalse(RecorderClock.needsSet(phone.minusSeconds(5), phone))
        assertFalse(RecorderClock.needsSet(phone.plusSeconds(5), phone))
        assertTrue(RecorderClock.needsSet(phone.minusSeconds(6), phone))
        assertTrue(RecorderClock.needsSet(phone.plusSeconds(6), phone))
        assertTrue(RecorderClock.needsSet(null, phone))
    }

    @Test fun failedAttemptsDoubleTheWaitUpToTheCap() {
        assertEquals(AutoSyncBackoff.INTERVAL_MS, AutoSyncBackoff.delayMs(0))
        assertEquals(60_000L, AutoSyncBackoff.delayMs(1))
        assertEquals(120_000L, AutoSyncBackoff.delayMs(2))
        assertEquals(480_000L, AutoSyncBackoff.delayMs(4))
        assertEquals(AutoSyncBackoff.MAX_MS, AutoSyncBackoff.delayMs(5))
        assertEquals(AutoSyncBackoff.MAX_MS, AutoSyncBackoff.delayMs(Int.MAX_VALUE))
    }
}
