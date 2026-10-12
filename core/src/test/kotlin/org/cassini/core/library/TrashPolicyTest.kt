package org.cassini.core.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class TrashPolicyTest {
    private val rome = ZoneId.of("Europe/Rome")

    private fun at(text: String): Instant = LocalDateTime.parse(text).atZone(rome).toInstant()

    private fun daysLeft(trashed: String, now: String) = TrashPolicy.daysLeft(at(trashed).toEpochMilli(), at(now), rome)

    private fun expired(trashed: String, now: String) = TrashPolicy.isExpired(at(trashed).toEpochMilli(), at(now), rome)

    @Test fun board09() {
        // "Deleted 2 Oct · 23 days left" on 9 Oct.
        val trashed = at("2026-10-02T18:20").toEpochMilli()
        val now = at("2026-10-09T15:00")
        assertEquals(23, TrashPolicy.daysLeft(trashed, now, rome))
        assertEquals(DayKey.Date(LocalDate.of(2026, 10, 2), withYear = false), DayGroups.key(trashed, now, rome))
    }

    @Test fun sameDayAndYesterday() {
        assertEquals(30, daysLeft("2026-10-09T00:00", "2026-10-09T23:59"))
        assertEquals(29, daysLeft("2026-10-08T23:59", "2026-10-09T00:00"))
        assertFalse(expired("2026-10-09T00:00", "2026-10-09T23:59"))
    }

    @Test fun day30IsExpired() {
        assertEquals(1, daysLeft("2026-10-02T23:59", "2026-10-31T23:59"))
        assertFalse(expired("2026-10-02T23:59", "2026-10-31T23:59"))
        assertEquals(0, daysLeft("2026-10-02T23:59", "2026-11-01T00:00"))
        assertTrue(expired("2026-10-02T23:59", "2026-11-01T00:00"))
        assertEquals(0, daysLeft("2026-10-02T00:00", "2027-01-01T00:00"))
    }

    @Test fun calendarDaysAcrossDst() {
        // 29 Mar 2026 has 23 hours: 22.5 hours later is already the next day.
        assertEquals(29, daysLeft("2026-03-28T23:30", "2026-03-29T23:00"))
        // 25 Oct 2026 has 25 hours: 48 h 40 min later is still only the next day.
        assertEquals(29, daysLeft("2026-10-24T00:10", "2026-10-25T23:50"))
        // Trashed in summer time, expired at local midnight in winter time.
        assertFalse(expired("2026-10-02T12:00", "2026-10-31T23:59:59"))
        assertTrue(expired("2026-10-02T12:00", "2026-11-01T00:00"))
    }

    @Test fun aClockSetBackAddsNoDays() {
        assertEquals(30, daysLeft("2026-10-12T10:00", "2026-10-09T10:00"))
    }

    @Test fun purgeCutoffMatchesIsExpired() {
        val moments = listOf(
            "2026-11-01T12:00",
            "2026-11-01T00:00",
            "2026-03-29T03:30",
            "2026-10-25T02:30",
            "2027-01-01T00:00",
        )
        for (now in moments) {
            val instant = at(now)
            val cutoff = TrashPolicy.purgeCutoff(instant, rome)
            val probes = (-3L..3L).flatMap { offset -> listOf(cutoff + offset, cutoff + offset * 3_600_000) } +
                (0L..40L).map { instant.toEpochMilli() - it * 86_400_000 }
            for (trashed in probes) {
                assertEquals("$now $trashed", TrashPolicy.isExpired(trashed, instant, rome), trashed < cutoff)
            }
        }
        // On 1 Nov the notes trashed up to the end of 2 Oct expire.
        assertEquals(at("2026-10-03T00:00").toEpochMilli(), TrashPolicy.purgeCutoff(at("2026-11-01T12:00"), rome))
    }
}
