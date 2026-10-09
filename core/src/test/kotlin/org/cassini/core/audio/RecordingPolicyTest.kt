package org.cassini.core.audio

import org.cassini.core.audio.RecordingPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingPolicyTest {
    private val minute = 60_000L
    private val floor = Limits.MIN_RECORDING_FREE_BYTES
    private val opus = RecordingPolicy.OPUS_BYTES_PER_SECOND
    private val aac = RecordingPolicy.AAC_BYTES_PER_SECOND
    private val plenty = 64L shl 30

    /** Free bytes that leave exactly [ms] of recording at [rate]. */
    private fun spaceFor(ms: Long, rate: Long = opus) = floor + ms * rate / 1000

    private fun decide(elapsedMs: Long, usableBytes: Long, rate: Long = opus) =
        RecordingPolicy.decide(elapsedMs, usableBytes, rate)

    @Test fun theNominalRatesAreOpus48AndAac32Kbps() {
        assertEquals(48_000L / 8, opus)
        assertEquals(32_000L / 8, aac)
    }

    @Test fun plentyOfSpaceAndTimeContinues() {
        assertEquals(Decision.Continue, decide(0, plenty))
        assertEquals(Decision.Continue, decide(60 * minute, plenty))
    }

    @Test fun storageLowStartsAtFifteenMinutesOfSpace() {
        assertEquals(Decision.StorageLow(15), decide(0, spaceFor(15 * minute)))
        assertEquals(Decision.Continue, decide(0, spaceFor(15 * minute) + opus / 1000 + 1))
        assertEquals(Decision.StorageLow(10), decide(0, spaceFor(12 * minute)))
        assertEquals(Decision.StorageLow(5), decide(0, spaceFor(minute)))
    }

    @Test fun theFloorItselfStillRecordsAndOneByteLessStops() {
        assertEquals(Decision.StorageLow(5), decide(0, floor))
        assertEquals(Decision.StopForSpace, decide(0, floor - 1))
        assertEquals(Decision.StopForSpace, decide(0, 0))
    }

    @Test fun theWarningComesFiveMinutesBeforeTheCap() {
        val cap = Limits.MAX_RECORDING_MS
        assertEquals(Decision.Continue, decide(cap - Limits.RECORDING_WARNING_MS - 1, plenty))
        // 1:55:00
        assertEquals(Decision.NearTimeLimit(5 * minute), decide(115 * minute, plenty))
        assertEquals(Decision.NearTimeLimit(1), decide(cap - 1, plenty))
    }

    @Test fun theCapStopsAtTwoHours() {
        assertEquals(Decision.StopAtTimeLimit, decide(120 * minute, plenty))
        assertEquals(Decision.StopAtTimeLimit, decide(121 * minute, plenty))
        assertEquals(Decision.StopAtTimeLimit, decide(120 * minute, floor - 1))
    }

    @Test fun withBothLimitsCloseTheFirstOneWarns() {
        // Four minutes to the cap, ten of space: the cap stops it first.
        assertEquals(Decision.NearTimeLimit(4 * minute), decide(116 * minute, spaceFor(10 * minute)))
        // Four minutes to the cap, three of space: space runs out first.
        assertEquals(Decision.StorageLow(5), decide(116 * minute, spaceFor(3 * minute)))
        // Storage low long before the cap is near.
        assertEquals(Decision.StorageLow(10), decide(30 * minute, spaceFor(10 * minute)))
    }

    @Test fun theSameBytesLastLongerForAac() {
        val bytes = spaceFor(10 * minute, opus)
        assertEquals(Decision.StorageLow(10), decide(0, bytes, opus))
        assertEquals(Decision.StorageLow(15), decide(0, bytes, aac))
        assertEquals(Decision.Continue, decide(0, spaceFor(16 * minute, aac), aac))
    }
}
