package io.codemyriad.cassini.core.library

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * D14 filters narrow typed-text hits and live only for the open search; they combine with AND (L-35, D99).
 * [starred] keeps starred notes only. [recordedByMe] keeps only cassini-ux `origin = own` ([Origin.imported] false),
 * so an Imported note never matches it (L-10) and a recovered capture of this device does.
 */
data class SearchFilters(
    val speaker: String? = null,
    val date: SearchDate = SearchDate.ANY,
    val recordedByMe: Boolean = false,
    val starred: Boolean = false,
) {
    fun includes(createdAt: Long, origin: Origin, today: LocalDate, zone: ZoneId, starred: Boolean = false): Boolean =
        marks(origin, starred) && date.includes(createdAt, today, zone)

    /** D95: a resolved calendar day, or null for unknown. Local labels have no viewer-zone conversion. */
    fun includes(recordedDay: LocalDate?, origin: Origin, starred: Boolean, today: LocalDate): Boolean =
        marks(origin, starred) && date.includes(recordedDay, today)

    /** [includes] for a note that is not starred. */
    fun includes(recordedDay: LocalDate?, origin: Origin, today: LocalDate): Boolean =
        includes(recordedDay, origin, starred = false, today)

    private fun marks(origin: Origin, starred: Boolean): Boolean =
        (!recordedByMe || !origin.imported) && (!this.starred || starred)
}

/**
 * Calendar days, rather than multiples of 24 hours: daylight-saving transitions belong to their local date. Each
 * choice starts at local midnight of its first day and ends with today: [TODAY], [WEEK] (7 days), [MONTH] (30 days)
 * and [YEAR] (from 1 January, L-35).
 */
enum class SearchDate {
    ANY,
    TODAY,
    WEEK,
    MONTH,
    YEAR,
    ;

    /** The first day included on [today]; null for [ANY]. */
    @Suppress("MagicNumber") // The exact 7/30-day choices from D14.
    fun firstDay(today: LocalDate): LocalDate? = when (this) {
        ANY -> null
        TODAY -> today
        WEEK -> today.minusDays(6)
        MONTH -> today.minusDays(29)
        YEAR -> today.withDayOfYear(1)
    }

    fun includes(createdAt: Long, today: LocalDate, zone: ZoneId): Boolean =
        this == ANY || includes(Instant.ofEpochMilli(createdAt).atZone(zone).toLocalDate(), today)

    fun includes(recordedDay: LocalDate?, today: LocalDate): Boolean {
        val first = firstDay(today) ?: return true
        return recordedDay != null && !recordedDay.isBefore(first) && !recordedDay.isAfter(today)
    }
}
