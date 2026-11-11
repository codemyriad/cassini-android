package io.codemyriad.cassini.core.library

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** D14 filters narrow typed-text hits and live only for the open search. */
data class SearchFilters(
    val speaker: String? = null,
    val date: SearchDate = SearchDate.ANY,
    val recordedByMe: Boolean = false,
) {
    fun includes(createdAt: Long, origin: Origin, today: LocalDate, zone: ZoneId): Boolean =
        (!recordedByMe || origin == Origin.RECORDED) && date.includes(createdAt, today, zone)

    /** D95: a resolved calendar day, or null for unknown. Local labels have no viewer-zone conversion. */
    fun includes(recordedDay: LocalDate?, origin: Origin, today: LocalDate): Boolean =
        (!recordedByMe || origin == Origin.RECORDED) && date.includes(recordedDay, today)
}

/** Calendar days, rather than multiples of 24 hours: daylight-saving transitions belong to their local date. */
@Suppress("MagicNumber") // The exact 7/30-day choices from D14.
enum class SearchDate(private val days: Long?) {
    ANY(null),
    TODAY(1),
    WEEK(7),
    MONTH(30),
    ;

    fun includes(createdAt: Long, today: LocalDate, zone: ZoneId): Boolean =
        days == null || includes(Instant.ofEpochMilli(createdAt).atZone(zone).toLocalDate(), today)

    fun includes(recordedDay: LocalDate?, today: LocalDate): Boolean {
        val count = days ?: return true
        val first = today.minusDays(count - 1)
        return recordedDay != null && !recordedDay.isBefore(first) && !recordedDay.isAfter(today)
    }
}
