package io.codemyriad.cassini.core.library

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
}

/** Calendar days, rather than multiples of 24 hours: daylight-saving transitions belong to their local date. */
enum class SearchDate(private val days: Long?) {
    ANY(null),
    TODAY(1),
    WEEK(7),
    MONTH(30),
    ;

    fun includes(createdAt: Long, today: LocalDate, zone: ZoneId): Boolean {
        val count = days ?: return true
        val first = today.minusDays(count - 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val after = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return createdAt >= first && createdAt < after
    }
}
