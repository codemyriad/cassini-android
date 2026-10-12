package org.cassini.core.library

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The Library day header of a note: "Today", "Yesterday", or a date shown [withYear] when not in the current year. */
sealed interface DayKey {
    data object Today : DayKey
    data object Yesterday : DayKey
    data class Date(val date: LocalDate, val withYear: Boolean) : DayKey
}

/** Groups notes by the local calendar day of their creation time, in a given zone (features.md section 1). */
object DayGroups {
    /** The day header of [epochMs] seen at [now] in [zone]. Calendar days, so a DST change never shifts a note. */
    fun key(epochMs: Long, now: Instant, zone: ZoneId): DayKey {
        val day = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        return when (day) {
            today -> DayKey.Today
            today.minusDays(1) -> DayKey.Yesterday
            else -> DayKey.Date(day, withYear = day.year != today.year)
        }
    }

    /**
     * Splits [items], already ordered newest first, into runs of consecutive items with the same [DayKey]. The order
     * inside and between groups is kept.
     */
    fun <T> group(items: List<T>, now: Instant, zone: ZoneId, epochMs: (T) -> Long): List<Pair<DayKey, List<T>>> {
        val groups = mutableListOf<Pair<DayKey, MutableList<T>>>()
        for (item in items) {
            val key = key(epochMs(item), now, zone)
            val last = groups.lastOrNull()
            if (last != null && last.first == key) last.second.add(item) else groups.add(key to mutableListOf(item))
        }
        return groups
    }
}
