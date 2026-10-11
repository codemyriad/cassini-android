package org.cassini.core.audio

import org.cassini.core.audio.RecordingPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RecordingPolicyTest {
    private val minute = 60_000L
    private val hour = 60 * minute
    private val floor = Limits.MIN_RECORDING_FREE_BYTES
    private val opus = RecordingPolicy.OPUS_BYTES_PER_SECOND
    private val aac = RecordingPolicy.AAC_BYTES_PER_SECOND
    private val plenty = 64L shl 30

    /** Free bytes that leave exactly [ms] of recording at [rate]. */
    private fun spaceFor(ms: Long, rate: Long = opus) = floor + ms * rate / 1000

    private fun decide(usableBytes: Long, rate: Long = opus) = RecordingPolicy.decide(usableBytes, rate)

    @Test fun theNominalRatesAreOpus48AndAac32Kbps() {
        assertEquals(48_000L / 8, opus)
        assertEquals(32_000L / 8, aac)
    }

    @Test fun plentyOfSpaceContinues() {
        assertEquals(Decision.Continue, decide(plenty))
    }

    @Test fun storageLowStartsAtFifteenMinutesOfSpace() {
        assertEquals(Decision.StorageLow(15), decide(spaceFor(15 * minute)))
        assertEquals(Decision.Continue, decide(spaceFor(15 * minute) + opus / 1000 + 1))
        assertEquals(Decision.StorageLow(10), decide(spaceFor(12 * minute)))
        assertEquals(Decision.StorageLow(5), decide(spaceFor(minute)))
    }

    @Test fun theFloorItselfStillRecordsAndOneByteLessStops() {
        assertEquals(Decision.StorageLow(5), decide(floor))
        assertEquals(Decision.StopForSpace, decide(floor - 1))
        assertEquals(Decision.StopForSpace, decide(0))
    }

    @Test fun theSameBytesLastLongerForAac() {
        val bytes = spaceFor(10 * minute, opus)
        assertEquals(Decision.StorageLow(10), decide(bytes, opus))
        assertEquals(Decision.StorageLow(15), decide(bytes, aac))
        assertEquals(Decision.Continue, decide(spaceFor(16 * minute, aac), aac))
    }

    /**
     * A synthetic 40 h recording on a phone with 1 GB free, decided once a minute as the file grows: it never stops
     * for time, warns only in the last 15 min of space, and stops only at the floor.
     */
    @Test fun aLongRecordingStopsOnlyAtTheStorageFloor() {
        val start = floor + 1_000_000_000L
        var stoppedAt = -1L
        var warnedAt = -1L
        var elapsed = 0L
        while (elapsed <= 60 * hour) {
            val usable = start - elapsed / 1000 * opus
            when (decide(usable)) {
                Decision.Continue -> assertEquals(-1L, warnedAt)
                is Decision.StorageLow -> if (warnedAt < 0) warnedAt = elapsed
                Decision.StopAtFileLimit -> fail("An Opus file has no limit")
                Decision.StopForSpace -> {
                    stoppedAt = elapsed
                    break
                }
            }
            elapsed += minute
        }
        val runsOutAt = 1_000_000_000L / opus * 1000
        assertTrue("Ran ${stoppedAt / hour} h", stoppedAt > 40 * hour)
        assertTrue(stoppedAt in runsOutAt..runsOutAt + minute)
        assertTrue(warnedAt in runsOutAt - 16 * minute..runsOutAt - 14 * minute)
    }

    @Test fun anAacFileStopsAtItsLimitAndOnlyThere() {
        val max = RecordingPolicy.AAC_MAX_FILE_BYTES
        assertTrue(max < 1L shl 32)
        assertEquals(74L, max / aac / 3600)
        assertEquals(Decision.Continue, decide2(plenty, max - 1, max))
        assertEquals(Decision.StopAtFileLimit, decide2(plenty, max, max))
        // The storage stop comes first; the storage warning does not count the file.
        assertEquals(Decision.StopForSpace, decide2(floor - 1, max, max))
        assertEquals(Decision.Continue, decide2(plenty, max - 10, max))
        // An Opus capture has no file limit.
        assertEquals(Decision.Continue, RecordingPolicy.decide(plenty, opus, 100L shl 30))
    }

    private fun decide2(usable: Long, file: Long, max: Long) = RecordingPolicy.decide(usable, aac, file, max)
}
