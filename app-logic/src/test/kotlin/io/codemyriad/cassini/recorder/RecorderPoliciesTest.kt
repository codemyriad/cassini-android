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

    @Test fun idleSyncsDoubleTheWaitUpToTheCap() {
        assertEquals(AutoSyncBackoff.INTERVAL_MS, AutoSyncBackoff.delayMs(0, idle = 0))
        assertEquals(360_000L, AutoSyncBackoff.delayMs(0, idle = 1))
        assertEquals(720_000L, AutoSyncBackoff.delayMs(0, idle = 2))
        assertEquals(AutoSyncBackoff.MAX_MS, AutoSyncBackoff.delayMs(0, idle = 3))
        assertEquals(AutoSyncBackoff.MAX_MS, AutoSyncBackoff.delayMs(0, idle = Int.MAX_VALUE))
        // A failure counts first.
        assertEquals(60_000L, AutoSyncBackoff.delayMs(1, idle = 3))
    }

    @Test fun aWaitingTranscriptIsTriedAgainSoonThenEveryFiveMinutes() {
        assertEquals(30_000L, TranscriptPushBackoff.delayMs(1))
        assertEquals(60_000L, TranscriptPushBackoff.delayMs(2))
        assertEquals(120_000L, TranscriptPushBackoff.delayMs(3))
        assertEquals(240_000L, TranscriptPushBackoff.delayMs(4))
        assertEquals(TranscriptPushBackoff.MAX_MS, TranscriptPushBackoff.delayMs(5))
        assertEquals(TranscriptPushBackoff.MAX_MS, TranscriptPushBackoff.delayMs(Int.MAX_VALUE))
        // The push gives up after about 1 h 50 min of waits: it does not poll the recorder for the life of the process.
        val waited = (1 until TranscriptPushBackoff.MAX_FAILURES).sumOf(TranscriptPushBackoff::delayMs)
        assertEquals(6_450_000L, waited)
    }

    @Test fun aFirmwareThatRefusedTranscriptsIsAskedAgainDailyOrAfterAChange() {
        val day = TranscriptUploadProbe.RETRY_MS
        assertTrue(TranscriptUploadProbe.mayUpload(null, null, "openfw_v5", 0))
        assertFalse(TranscriptUploadProbe.mayUpload("openfw_v5", 1_000, "openfw_v5", 1_000 + day - 1))
        assertTrue(TranscriptUploadProbe.mayUpload("openfw_v5", 1_000, "openfw_v5", 1_000 + day))
        assertTrue(TranscriptUploadProbe.mayUpload("openfw_v5", 1_000, "openfw_v6", 2_000))
        // A clock that went back does not stop the uploads for good.
        assertTrue(TranscriptUploadProbe.mayUpload("openfw_v5", 1_000, "openfw_v5", 500))
        // A refusal stored without its time (an older state file) is asked again.
        assertTrue(TranscriptUploadProbe.mayUpload("openfw_v5", null, "openfw_v5", 0))
        // A recorder that advertises the target (rec=1 in PONG) is used at once, even after an older refusal.
        assertTrue(TranscriptUploadProbe.mayUpload("openfw_v5", 1_000, "openfw_v5", 1_001, advertised = true))
    }
}
