package org.cassini.core.library

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class DayGroupsTest {
    private val rome = ZoneId.of("Europe/Rome")

    private fun day(month: Int, day: Int, year: Int = 2026, withYear: Boolean = false) =
        DayKey.Date(LocalDate.of(year, month, day), withYear)

    private fun at(text: String, zone: ZoneId = rome): Instant = LocalDateTime.parse(text).atZone(zone).toInstant()

    private fun key(created: String, now: String, zone: ZoneId = rome) =
        DayGroups.key(at(created, zone).toEpochMilli(), at(now, zone), zone)

    @Test fun todayYesterdayThisYearOtherYear() {
        assertEquals(DayKey.Today, key("2026-10-09T00:00", "2026-10-09T23:59:59"))
        assertEquals(DayKey.Yesterday, key("2026-10-08T10:02", "2026-10-09T15:00"))
        assertEquals(day(10, 5), key("2026-10-05T08:14", "2026-10-09T15:00"))
        assertEquals(day(10, 9, 2025, withYear = true), key("2025-10-09T08:14", "2026-10-09T15:00"))
    }

    @Test fun acrossMidnight() {
        assertEquals(DayKey.Yesterday, key("2026-10-08T23:59:59.999", "2026-10-09T00:00"))
        assertEquals(DayKey.Today, key("2026-10-09T00:00", "2026-10-09T00:00:00.001"))
        assertEquals(day(10, 7), key("2026-10-07T23:59", "2026-10-09T00:01"))
    }

    @Test fun acrossTheYearBoundary() {
        assertEquals(DayKey.Yesterday, key("2026-12-31T23:50", "2027-01-01T00:05"))
        assertEquals(day(12, 30, withYear = true), key("2026-12-30T12:00", "2027-01-01T00:05"))
        assertEquals(day(1, 1, 2027), key("2027-01-01T09:00", "2027-01-03T09:00"))
    }

    @Test fun romeDstChanges() {
        // 29 Mar 2026: 02:00 CET jumps to 03:00 CEST (a 23-hour day).
        assertEquals(DayKey.Today, key("2026-03-29T00:30", "2026-03-29T23:59"))
        assertEquals(DayKey.Yesterday, key("2026-03-28T23:30", "2026-03-29T00:10"))
        assertEquals(DayKey.Yesterday, key("2026-03-28T00:10", "2026-03-29T23:50"))
        // 25 Oct 2026: 03:00 CEST falls back to 02:00 CET (a 25-hour day). Both 02:30 instants are the same day.
        val early = LocalDateTime.parse("2026-10-25T02:30").atZone(rome).withEarlierOffsetAtOverlap().toInstant()
        val late = LocalDateTime.parse("2026-10-25T02:30").atZone(rome).withLaterOffsetAtOverlap().toInstant()
        assertEquals(DayKey.Today, DayGroups.key(early.toEpochMilli(), late, rome))
        assertEquals(DayKey.Yesterday, key("2026-10-24T00:05", "2026-10-25T23:55"))
    }

    @Test fun theZoneDecidesTheDay() {
        // 23:30 UTC on 8 Oct is already 9 Oct in Rome.
        val created = LocalDateTime.parse("2026-10-08T23:30").toInstant(ZoneOffset.UTC).toEpochMilli()
        val now = LocalDateTime.parse("2026-10-09T08:00").toInstant(ZoneOffset.UTC)
        assertEquals(DayKey.Yesterday, DayGroups.key(created, now, ZoneOffset.UTC))
        assertEquals(DayKey.Today, DayGroups.key(created, now, rome))
    }

    @Test fun groupsKeepOrder() {
        val now = at("2026-10-09T15:00")
        val notes = listOf(
            "2026-10-09T10:02",
            "2026-10-09T08:40",
            "2026-10-08T15:10",
            "2026-10-08T11:00",
            "2026-10-05T08:14",
        ).map { at(it).toEpochMilli() }
        val groups = DayGroups.group(notes, now, rome) { it }
        assertEquals(
            listOf(DayKey.Today, DayKey.Yesterday, day(10, 5)),
            groups.map { it.first },
        )
        assertEquals(listOf(notes.take(2), notes.subList(2, 4), notes.drop(4)), groups.map { it.second })
        assertEquals(emptyList<Pair<DayKey, List<Long>>>(), DayGroups.group(emptyList<Long>(), now, rome) { it })
    }
}
