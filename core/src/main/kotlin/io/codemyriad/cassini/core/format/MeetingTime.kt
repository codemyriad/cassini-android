package io.codemyriad.cassini.core.format

import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/** D95: an attested instant or a site-local label, never the file's packaging time. */
data class MeetingTime(val source: String, val value: String) {
    val instant: Instant? get() = if (source == LOCAL) null else offsetInstant(value)
    val label: String? get() = value.takeIf { source == LOCAL }

    fun day(zone: ZoneId): LocalDate? = instant?.atZone(zone)?.toLocalDate() ?: try {
        LocalDateTime.parse(value).toLocalDate()
    } catch (_: DateTimeParseException) {
        null
    }

    companion object {
        const val UTC = "recordedAtUtc"
        const val DATE = "DATE"
        const val LOCAL = "recordedAtLocal"
        private val offset = Regex(".*T.*(?:Z|[+-][0-9]{2}:[0-9]{2})$")

        fun utc(at: Instant): MeetingTime = MeetingTime(UTC, at.toString())

        /** Cached values retain offsets or local-label digits; no viewer zone is attached to a label. */
        fun cached(value: String?): MeetingTime? = value?.takeIf { it.isNotEmpty() }?.let {
            MeetingTime(if (offsetInstant(it) != null) UTC else LOCAL, it)
        }

        @Suppress("ReturnCount") // Ordered D95 fallback clauses, each rejecting unusable evidence.
        fun resolve(manifest: JSONObject?, comments: List<String>): MeetingTime? {
            val meeting = manifest?.optJSONObject("meeting") ?: JSONObject()
            val utc = (meeting.opt(UTC) as? String)?.trim()
            if (utc != null && utc.endsWith('Z') && offsetInstant(utc) != null) return MeetingTime(UTC, utc)
            val date = comments.firstOrNull { it.substringBefore('=').equals(DATE, ignoreCase = true) }
                ?.substringAfter('=', "")?.trim()
            val created = (meeting.opt("createdAtUtc") as? String)?.let(::offsetInstant)
            if (date != null && offsetInstant(date)?.let { it != created } == true) return MeetingTime(DATE, date)
            return (meeting.opt(LOCAL) as? String)?.takeIf { it.isNotEmpty() }?.let { MeetingTime(LOCAL, it) }
        }

        private fun offsetInstant(value: String): Instant? = if (!offset.matches(value)) {
            null
        } else {
            try {
                OffsetDateTime.parse(value).toInstant()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
}
