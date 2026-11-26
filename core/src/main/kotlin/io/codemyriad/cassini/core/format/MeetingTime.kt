package io.codemyriad.cassini.core.format

import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * D95: an attested instant or a site-local label, never the file's packaging time. D112 (L-87) adds [STARTED]: the
 * meeting start with its UTC offset (A4 `meeting.startedAt`), which a person may set in "Set date and time".
 */
data class MeetingTime(val source: String, val value: String) {
    val instant: Instant? get() = if (source == LOCAL) null else offsetInstant(value)
    val label: String? get() = value.takeIf { source == LOCAL }

    /** Preserve the source, including a local label that happens to contain an offset. */
    val cacheValue: String get() = "$source=$value"

    fun day(zone: ZoneId): LocalDate? = calendarTime(zone)?.toLocalDate()

    /** UTC is viewed in [zone]; a floating local label keeps its written calendar fields, with no assumed zone. */
    fun calendarTime(zone: ZoneId): LocalDateTime? = instant?.atZone(zone)?.toLocalDateTime() ?: localCalendarTime()

    private fun localCalendarTime(): LocalDateTime? = try {
        LocalDateTime.parse(value)
    } catch (_: DateTimeParseException) {
        val prefix = localPrefix.find(value)?.value?.takeIf { source == LOCAL }
        if (prefix == null) {
            null
        } else {
            try {
                LocalDateTime.parse(prefix.replace(' ', 'T'))
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }

    companion object {
        const val UTC = "recordedAtUtc"
        const val DATE = "DATE"
        const val LOCAL = "recordedAtLocal"

        /** D112 / A4: `meeting.startedAt`, or `x["codemyriad.io"].meeting.startedAt` set by the person. */
        const val STARTED = "startedAt"

        /** The cassini-ux private namespace (Library v2 `x["codemyriad.io"]`, MAPPING.md). */
        const val UX_NAMESPACE = "codemyriad.io"
        private const val USER = "user"
        private const val IMPORT_TIME = "import-time"
        private const val MILLIS_FRACTION_WIDTH = 4 // Decimal point and three digits, as in the pinned reader.
        private val localPrefix = Regex("""^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}""")
        private val offset = Regex(
            """^(\d{4}-\d{2}-\d{2})[Tt ](\d{2}:\d{2}:\d{2})(\.\d+)?([Zz]|[+-]\d{2}:\d{2})$""",
        )

        fun utc(at: Instant): MeetingTime = MeetingTime(UTC, at.toString())

        /** A start set by the person (D112), RFC 3339 with its offset; null unless [value] is one. */
        fun started(value: String?): MeetingTime? =
            value?.let(StartedAt::parse)?.let { MeetingTime(STARTED, StartedAt.format(it)) }

        /** Cached values retain offsets or local-label digits; no viewer zone is attached to a label. */
        fun cached(value: String?): MeetingTime? = value?.takeIf { it.isNotEmpty() }?.let {
            val source = it.substringBefore('=')
            if (source in setOf(UTC, DATE, LOCAL, STARTED)) {
                MeetingTime(source, it.substringAfter('='))
            } else {
                MeetingTime(if (offsetInstant(it) != null) UTC else LOCAL, it)
            }
        }

        /**
         * The D95 order (`recordedAtUtc`, then a DATE that is not the packaging instant, then the `recordedAtLocal`
         * label), preceded by the D112 start (LIBRARY-RULES R6.2): a `startedAt` the person set in
         * `x["codemyriad.io"].meeting` wins over everything, then the A4 `meeting.startedAt`. A file whose Library
         * name prefix is the import time (`namePrefixSource: import-time`) has no known start of its own, so its
         * `meeting.startedAt`, DATE and `recordedAtLocal` are legacy publish-time stamps and are ignored.
         */
        // Ordered fallback clauses, each rejecting unusable evidence.
        @Suppress("ReturnCount", "CyclomaticComplexMethod")
        fun resolve(manifest: JSONObject?, comments: List<String>): MeetingTime? {
            val meeting = manifest?.optJSONObject("meeting") ?: JSONObject()
            val ux = manifest?.optJSONObject("x")?.optJSONObject(UX_NAMESPACE)
            val uxMeeting = ux?.optJSONObject("meeting")
            val userSet = uxMeeting?.opt("startedAtSource") == USER
            if (userSet) started(uxMeeting?.opt(STARTED) as? String)?.let { return it }
            val importTime = !userSet && (ux?.opt("namePrefixSource") == IMPORT_TIME)
            if (!importTime) started(meeting.opt(STARTED) as? String)?.let { return it }
            val utc = (meeting.opt(UTC) as? String)?.trim()
            if (utc != null && utc.endsWith('Z') && offsetInstant(utc) != null) return MeetingTime(UTC, utc)
            if (importTime) return null
            val date = comments.firstOrNull { it.substringBefore('=').equals(DATE, ignoreCase = true) }
                ?.substringAfter('=', "")?.trim()
            val created = (meeting.opt("createdAtUtc") as? String)?.let(::offsetInstant)
            if (date != null && offsetInstant(date)?.let { it != created } == true) return MeetingTime(DATE, date)
            return (meeting.opt(LOCAL) as? String)?.takeIf { it.isNotEmpty() }?.let { MeetingTime(LOCAL, it) }
        }

        /** Match the pinned reader's RFC 3339 grammar and millisecond comparison precision. */
        private fun offsetInstant(value: String): Instant? {
            val match = offset.matchEntire(value.trim()) ?: return null
            val (date, time, fraction) = match.destructured
            val zone = match.groupValues.last()
            val normalized = "${date}T$time${fraction.take(MILLIS_FRACTION_WIDTH)}${if (zone == "z") "Z" else zone}"
            return try {
                OffsetDateTime.parse(normalized).toInstant()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
}
