package org.cassini.core.audio

import org.cassini.core.audio.RecordingSpace.Approx
import org.cassini.core.audio.RecordingSpace.Limit
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingSpaceTest {
    private val minute = 60_000L
    private val hour = 60 * minute
    private val floor = Limits.MIN_RECORDING_FREE_BYTES
    private val opus = RecordingPolicy.OPUS_BYTES_PER_SECOND

    @Test fun plentyOfSpaceShowsTheSpaceInWholeHours() {
        // Board 01: "Space for about 31 h more." at 24:07 into an Opus recording, with the cap further away.
        val space = RecordingSpace.of(24 * minute + 7_000, floor + 31 * 3600 * opus + 1_000_000, opus)
        assertEquals(Limit.CAP, space.limit)
        val noCap = space.copy(capMs = Long.MAX_VALUE)
        assertEquals(Limit.SPACE, noCap.limit)
        assertEquals(Approx(31, 0), noCap.approx)
    }

    @Test fun theCapComesFirstWithEnoughSpace() {
        val space = RecordingSpace.of(25 * minute, floor + 10L * 3600 * opus, opus)
        assertEquals(Limit.CAP, space.limit)
        assertEquals(95 * minute, space.remainingMs)
        assertEquals(Approx(1, 35), space.approx)
    }

    @Test fun theSmallerLimitWins() {
        val space = RecordingSpace.of(0, floor + 40 * 60 * opus, opus)
        assertEquals(Limit.SPACE, space.limit)
        assertEquals(40 * minute, space.remainingMs)
        assertEquals(Approx(0, 40), space.approx)
    }

    @Test fun aTieCountsAsTheCap() {
        assertEquals(Limit.CAP, RecordingSpace(hour, hour).limit)
    }

    @Test fun spaceBelowTheFloorIsNone() {
        assertEquals(0L, RecordingSpace.of(0, floor - 1, opus).spaceMs)
        assertEquals(0L, RecordingSpace.of(0, floor, opus).spaceMs)
        assertEquals(1000L, RecordingSpace.of(0, floor + opus, opus).spaceMs)
        assertEquals(0L, RecordingSpace.of(3 * hour, Long.MAX_VALUE / 2000, opus).capMs)
    }

    @Test fun aacGrowsSlowerSoTheSameSpaceLastsLonger() {
        val bytes = floor + 60L * 60 * 6000
        assertEquals(hour, RecordingSpace.of(0, bytes, opus).spaceMs)
        assertEquals(90 * minute, RecordingSpace.of(0, bytes, RecordingPolicy.AAC_BYTES_PER_SECOND).spaceMs)
    }

    @Test fun roundingIsWholeHoursFromTwoHoursElseFiveMinutesDown() {
        assertEquals(Approx(31, 0), RecordingSpace.approx(31 * hour + 59 * minute))
        assertEquals(Approx(2, 0), RecordingSpace.approx(2 * hour))
        assertEquals(Approx(2, 0), RecordingSpace.approx(2 * hour + 59 * minute))
        assertEquals(Approx(1, 55), RecordingSpace.approx(2 * hour - 1))
        assertEquals(Approx(1, 35), RecordingSpace.approx(99 * minute))
        assertEquals(Approx(1, 0), RecordingSpace.approx(hour + 4 * minute))
        assertEquals(Approx(0, 10), RecordingSpace.approx(14 * minute + 59_999))
        assertEquals(Approx(0, 5), RecordingSpace.approx(5 * minute))
    }

    @Test fun theShortestHintIsFiveMinutes() {
        assertEquals(Approx(0, 5), RecordingSpace.approx(4 * minute + 59_999))
        assertEquals(Approx(0, 5), RecordingSpace.approx(0))
        assertEquals(Approx(0, 5), RecordingSpace.approx(-1))
        assertEquals(5L, RecordingSpace.approx(0).totalMinutes)
        assertEquals(95L, Approx(1, 35).totalMinutes)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aRateOfZeroIsRefused() {
        RecordingSpace.of(0, floor, 0)
    }
}
