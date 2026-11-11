package io.codemyriad.cassini.core.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SearchFiltersTest {
    private val zone = ZoneId.of("Europe/Rome")

    @Test fun eachRangeIncludesItsFirstMidnightAndExcludesTomorrow() {
        for (today in listOf(LocalDate.of(2026, 3, 29), LocalDate.of(2026, 10, 25))) {
            for ((range, days) in listOf(SearchDate.TODAY to 1L, SearchDate.WEEK to 7L, SearchDate.MONTH to 30L)) {
                val first = today.minusDays(days - 1).atStartOfDay(zone).toInstant().toEpochMilli()
                val tomorrow = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                assertFalse("$today/$range before first", range.includes(first - 1, today, zone))
                assertTrue("$today/$range first", range.includes(first, today, zone))
                assertTrue("$today/$range end", range.includes(tomorrow - 1, today, zone))
                assertFalse("$today/$range tomorrow", range.includes(tomorrow, today, zone))
            }
        }
    }

    @Test fun calendarWindowsDoNotSubtractFixedDaysAcrossDaylightSaving() {
        val today = LocalDate.of(2026, 3, 30)
        val first = LocalDate.of(2026, 3, 24).atStartOfDay(zone).toInstant().toEpochMilli()
        assertFalse(SearchDate.WEEK.includes(first - 1, today, zone))
        assertTrue(SearchDate.WEEK.includes(first, today, zone))
        val otherZone = ZoneId.of("America/New_York")
        val sameInstant = today.atStartOfDay(zone).toInstant().toEpochMilli()
        assertTrue(SearchDate.TODAY.includes(sameInstant, today, zone))
        assertFalse(SearchDate.TODAY.includes(sameInstant, today, otherZone))
    }

    @Test fun unknownRecordingDatesDoNotPassABoundedFilter() {
        val today = LocalDate.of(2026, 11, 11)
        assertTrue(SearchFilters().includes(null, Origin.IMPORTED_DOCUMENT, today))
        assertTrue(SearchFilters(recordedByMe = true).includes(null, Origin.RECORDED, today))
        for (range in listOf(SearchDate.TODAY, SearchDate.WEEK, SearchDate.MONTH)) {
            assertFalse(SearchFilters(date = range).includes(null, Origin.RECORDED, today))
            assertTrue(SearchFilters(date = range).includes(today, Origin.IMPORTED_DOCUMENT, today))
            assertFalse(SearchFilters(date = range).includes(today.plusDays(1), Origin.RECORDED, today))
        }
    }

    @Test fun midnightGapAndFoldKeepTheWholeCalendarDay() {
        for ((name, day) in listOf(
            "America/Santiago" to LocalDate.of(2023, 9, 3),
            "America/Havana" to LocalDate.of(2024, 11, 3),
        )) {
            val zone = ZoneId.of(name)
            val first = day.atStartOfDay(zone).toInstant()
            val after = day.plusDays(1).atStartOfDay(zone).toInstant()
            assertFalse(name, SearchDate.TODAY.includes(first.toEpochMilli() - 1, day, zone))
            assertTrue(name, SearchDate.TODAY.includes(first.toEpochMilli(), day, zone))
            assertTrue(name, SearchDate.TODAY.includes(first.plusSeconds(3600).toEpochMilli(), day, zone))
            assertTrue(name, SearchDate.TODAY.includes(after.toEpochMilli() - 1, day, zone))
            assertFalse(name, SearchDate.TODAY.includes(after.toEpochMilli(), day, zone))
        }
    }

    @Test fun recordedByMeAndDateMustBothMatch() {
        val today = LocalDate.of(2026, 11, 11)
        val at = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val filters = SearchFilters(date = SearchDate.TODAY, recordedByMe = true)
        assertTrue(filters.includes(at, Origin.RECORDED, today, zone))
        for (origin in listOf(Origin.IMPORTED_AUDIO, Origin.IMPORTED_DOCUMENT, Origin.RECOVERED)) {
            assertFalse(origin.name, filters.includes(at, origin, today, zone))
        }
        assertFalse(filters.includes(at - 1, Origin.RECORDED, today, zone))
        assertTrue(SearchFilters().includes(Long.MIN_VALUE, Origin.IMPORTED_AUDIO, today, zone))
        assertTrue(SearchFilters().includes(Long.MAX_VALUE, Origin.RECORDED, today, zone))
    }
}
