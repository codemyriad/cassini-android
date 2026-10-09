package org.cassini.core.library

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * How long a trashed note stays in Trash (architecture.md section 4.10). Days are calendar days in the device zone, so
 * a DST change never moves the purge: a note trashed on 2 Oct has 23 days left on 9 Oct and expires on 1 Nov.
 */
object TrashPolicy {
    const val PURGE_DAYS = 30

    /**
     * Days left before the note trashed at [trashedAtMs] is deleted for good, seen at [now] in [zone]: [PURGE_DAYS] on
     * the day it was trashed, 0 from the 30th day after. Clamped to 0..[PURGE_DAYS] (a clock set back cannot add days).
     */
    fun daysLeft(trashedAtMs: Long, now: Instant, zone: ZoneId): Int {
        val trashed = Instant.ofEpochMilli(trashedAtMs).atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        val elapsed = ChronoUnit.DAYS.between(trashed, today)
        return (PURGE_DAYS - elapsed).coerceIn(0, PURGE_DAYS.toLong()).toInt()
    }

    fun isExpired(trashedAtMs: Long, now: Instant, zone: ZoneId): Boolean = daysLeft(trashedAtMs, now, zone) == 0

    /**
     * The epoch ms before which a trashed note is expired at [now] in [zone]: the start of the local day
     * `PURGE_DAYS - 1` days before today. `trashed_at < purgeCutoff` is the same test as [isExpired].
     */
    fun purgeCutoff(now: Instant, zone: ZoneId): Long =
        now.atZone(zone).toLocalDate().minusDays(PURGE_DAYS - 1L).atStartOfDay(zone).toInstant().toEpochMilli()
}
