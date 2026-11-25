package io.codemyriad.cassini.core.format

import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * The D112 (L-87) meeting start: RFC 3339 with the person's UTC offset, seconds floored, no fraction
 * (`meeting.schema.json` `startedAt`, aligned with A4 and S-12 Q17). Offsets run from -12:00 to +14:00; `-00:00`
 * (unknown offset) is refused. Written as `±HH:MM`, so a zero offset is `+00:00` like DATE (S-12 Q8); `Z` is read.
 */
object StartedAt {
    private val grammar = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})$""")
    private val writer = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssxxx")
    private val local = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss")
    private const val MIN_OFFSET_SECONDS = -12 * 3600
    private const val MAX_OFFSET_SECONDS = 14 * 3600

    /** [value] as an offset date-time with its seconds floored, or null unless it is a valid D112 start. */
    fun parse(value: String): OffsetDateTime? {
        val text = value.trim().takeIf { grammar.matches(it) && !it.endsWith("-00:00") }
        val parsed = try {
            text?.let(OffsetDateTime::parse)
        } catch (_: DateTimeParseException) {
            null
        }
        return parsed?.takeIf { it.offset.totalSeconds in MIN_OFFSET_SECONDS..MAX_OFFSET_SECONDS }
            ?.truncatedTo(ChronoUnit.SECONDS)
    }

    /** `YYYY-MM-DDTHH:MM:SS±HH:MM`. */
    fun format(start: OffsetDateTime): String = start.truncatedTo(ChronoUnit.SECONDS).format(writer)

    /** The wall-clock part (`meeting.recordedAtLocal`): no offset, no zone, no fraction. */
    fun localPart(start: OffsetDateTime): String = start.truncatedTo(ChronoUnit.SECONDS).format(local)

    /**
     * The start a person picks: [date] at [time] (minutes; seconds dropped) in [zone], with that zone's offset on that
     * day. A time inside a daylight-saving gap moves forward, as `ZonedDateTime` resolves it.
     */
    fun of(date: LocalDate, time: LocalTime, zone: ZoneId): OffsetDateTime =
        ZonedDateTime.of(date, time.truncatedTo(ChronoUnit.MINUTES), zone).toOffsetDateTime()
}
