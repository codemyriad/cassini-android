package io.codemyriad.cassini.core.format

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class MeetingTimeTest {
    private fun manifest(utc: String? = null, local: String? = null): JSONObject = JSONObject().put(
        "meeting",
        JSONObject().put("createdAtUtc", "2026-11-11T12:09:00Z")
            .put("recordedAtUtc", utc).put("recordedAtLocal", local),
    )

    @Test fun cachedLocalLabelsNeverBecomeInstantsEvenWithAnOffset() {
        val label = "2026-11-09T10:31:48+02:00"
        val local = MeetingTime.resolve(org.json.JSONObject().put("meeting", org.json.JSONObject().put("recordedAtLocal", label)), emptyList())!!
        val cached = MeetingTime.cached(local.cacheValue)!!
        assertEquals(local, cached)
        assertNull(cached.instant)
        assertEquals(label, cached.label)
    }

    @Test fun priorityAndInstantEqualityFollowD95() {
        val utc = "2026-11-11T12:00:00Z"
        val local = "2026-11-11T13:00:00"
        val preferred = MeetingTime.resolve(manifest(utc, local), listOf("DATE=2026-11-10T12:00:00Z"))
        assertEquals(MeetingTime(MeetingTime.UTC, utc), preferred)
        val date = MeetingTime.resolve(manifest(local = local), listOf("date=2026-11-11T13:00:00+01:00"))
        assertEquals(MeetingTime.DATE, date?.source)
        val equalDate = MeetingTime.resolve(manifest(local = local), listOf("DATE=2026-11-11T13:09:00+01:00"))
        assertEquals(local, equalDate?.label)
        assertNull(MeetingTime.resolve(manifest(), listOf("DATE=2026-11-11T13:09:00+01:00")))
        assertNull(MeetingTime.resolve(manifest(), emptyList()))
        val plain = MeetingTime.resolve(null, listOf("DATE=$utc"))
        assertEquals(MeetingTime.DATE, plain?.source)
        assertEquals(utc, plain?.value)
    }

    @Test fun dateUsesThePinnedRfc3339GrammarAndPrecision() {
        assertNull(MeetingTime.resolve(manifest(), listOf("DATE=2026-11-11T12:00Z")))
        assertEquals(
            Instant.parse("2026-11-11T12:00:00.123Z"),
            MeetingTime.resolve(null, listOf("DATE=2026-11-11 12:00:00.123456789z"))?.instant,
        )
    }

    @Test fun midnightTransitionsKeepTheWholeLocalCalendarDay() {
        for ((zoneName, day) in listOf(
            "America/Santiago" to LocalDate.of(2023, 9, 3),
            "America/Havana" to LocalDate.of(2024, 11, 3),
        )) {
            val zone = ZoneId.of(zoneName)
            val first = day.atStartOfDay(zone).toInstant()
            val after = day.plusDays(1).atStartOfDay(zone).toInstant()
            assertEquals(day.minusDays(1), MeetingTime.utc(first.minusMillis(1)).day(zone))
            assertEquals(day, MeetingTime.utc(first).day(zone))
            assertEquals(day, MeetingTime.utc(after.minusMillis(1)).day(zone))
            assertEquals(day.plusDays(1), MeetingTime.utc(after).day(zone))
        }
        val gap = LocalDate.of(2023, 9, 3).atStartOfDay(ZoneId.of("America/Santiago"))
        assertEquals(1, gap.hour)
    }

    @Test fun malformedUtcAndOffsetlessDateDoNotInventAnInstant() {
        val local = "2026-11-11T23:00:00"
        val result = MeetingTime.resolve(manifest("invalidZ", local), listOf("DATE=2026-11-11T14:00:00"))!!
        assertEquals(local, result.value)
        assertNull(result.instant)
        assertEquals(LocalDate.of(2026, 11, 11), result.day(ZoneId.of("Pacific/Auckland")))
        assertEquals(local, MeetingTime.cached(local)?.label)
        assertEquals(Instant.parse("2026-11-11T12:00:00Z"), MeetingTime.cached("2026-11-11T13:00:00+01:00")?.instant)
    }
}
