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
