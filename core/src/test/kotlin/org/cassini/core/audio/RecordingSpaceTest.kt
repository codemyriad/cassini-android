package org.cassini.core.audio

import org.cassini.core.audio.RecordingSpace.Approx
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingSpaceTest {
    private val minute = 60_000L
    private val hour = 60 * minute
    private val floor = Limits.MIN_RECORDING_FREE_BYTES
    private val opus = RecordingPolicy.OPUS_BYTES_PER_SECOND
    private val aac = RecordingPolicy.AAC_BYTES_PER_SECOND

    @Test fun plentyOfSpaceShowsTheSpaceInWholeHours() {
        // Board 01: "Space for about 31 h more." in an Opus recording.
        val space = RecordingSpace.of(floor + 31 * 3600 * opus + 1_000_000, opus)
        assertEquals(Approx(31, 0), space.approx)
    }

    @Test fun theEstimateHasNoTwoHourClamp() {
        // 64 GB free gives about 3000 hours of Opus at 48 kb/s, not 2 h.
        val space = RecordingSpace.of(floor + 64_000_000_000L, opus)
        assertEquals(64_000_000_000L * 1000 / opus, space.spaceMs)
        assertEquals(Approx(2962, 0), space.approx)
        // A free terabyte does not overflow.
        assertEquals(1L shl 40, RecordingSpace.of(floor + (1L shl 40), 1000).spaceMs)
    }

    @Test fun littleSpaceShowsMinutes() {
        val space = RecordingSpace.of(floor + 40 * 60 * opus, opus)
        assertEquals(40 * minute, space.spaceMs)
        assertEquals(Approx(0, 40), space.approx)
    }

    @Test fun spaceBelowTheFloorIsNone() {
        assertEquals(0L, RecordingSpace.of(floor - 1, opus).spaceMs)
        assertEquals(0L, RecordingSpace.of(floor, opus).spaceMs)
        assertEquals(1000L, RecordingSpace.of(floor + opus, opus).spaceMs)
    }

    @Test fun aacGrowsSlowerSoTheSameSpaceLastsLonger() {
        val bytes = floor + 60L * 60 * 6000
        assertEquals(hour, RecordingSpace.of(bytes, opus).spaceMs)
        assertEquals(90 * minute, RecordingSpace.of(bytes, aac).spaceMs)
    }

    @Test fun theFileLimitWinsWhenItComesFirst() {
        val plenty = floor + 100_000_000_000L
        // An empty AAC file can take about 74 hours.
        val empty = RecordingSpace.of(plenty, aac, RecordingPolicy.AAC_MAX_FILE_BYTES)
        assertEquals(RecordingPolicy.AAC_MAX_FILE_BYTES * 1000 / aac, empty.spaceMs)
        assertEquals(Approx(74, 0), empty.approx)
        // Ten minutes of room left in the file.
        assertEquals(10 * minute, RecordingSpace.of(plenty, aac, 600 * aac).spaceMs)
        // Less free space than room in the file: the space wins.
        assertEquals(10 * minute, RecordingSpace.of(floor + 600 * aac, aac, RecordingPolicy.AAC_MAX_FILE_BYTES).spaceMs)
        assertEquals(0L, RecordingSpace.of(plenty, aac, -1).spaceMs)
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
        assertEquals(Approx(1000, 0), RecordingSpace.approx(1000 * hour + 59 * minute))
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
        RecordingSpace.of(floor, 0)
    }
}
