package io.codemyriad.cassini.note

import io.codemyriad.cassini.core.format.StartedAt
import io.codemyriad.cassini.data.db.NoteEntity
import io.codemyriad.cassini.data.notes.recordingTime
import io.codemyriad.cassini.ui.note.SetDateTimeUi
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * "Set date and time" (D112, L-87): what the sheet opens on, and what Save stores. Shared by the Library and the
 * Note screen, which both open the sheet from the meeting's actions.
 */
object StartPrefill {
    /**
     * The meeting's start when it has one (the start the person set, the recording clock or a label from the file);
     * otherwise the imported file's last-saved time minus the meeting's length, marked [SetDateTimeUi.fromFileSaved]
     * (a suggestion only: nothing is stored unless the person saves); otherwise [now]. Seconds are dropped.
     */
    fun of(note: NoteEntity, now: Instant, zone: ZoneId): SetDateTimeUi {
        val known = note.recordingTime()?.calendarTime(zone)
        val saved = note.sourceModifiedAt?.let { Instant.ofEpochMilli(it - note.durationMs.coerceAtLeast(0)) }
            ?.atZone(zone)?.toLocalDateTime()
        val at = known ?: saved ?: now.atZone(zone).toLocalDateTime()
        return SetDateTimeUi(
            noteId = note.id,
            date = at.toLocalDate(),
            time = at.toLocalTime().truncatedTo(ChronoUnit.MINUTES),
            fromFileSaved = known == null && saved != null,
        )
    }

    /** The value Save stores: [date] at [time] in [zone], RFC 3339 with that zone's offset on that day. */
    fun startedAt(date: LocalDate, time: LocalTime, zone: ZoneId): String =
        StartedAt.format(StartedAt.of(date, time, zone))

    /** [ui] as one saved-state string, so the sheet comes back after process death. */
    fun encode(ui: SetDateTimeUi): String =
        listOf(ui.noteId, ui.date, ui.time, ui.fromFileSaved).joinToString(SEPARATOR)

    /** The [encode]d sheet, or null for anything else. */
    fun decode(text: String?): SetDateTimeUi? {
        val parts = text?.split(SEPARATOR)?.takeIf { it.size == PARTS } ?: return null
        return try {
            SetDateTimeUi(
                noteId = parts.first(),
                date = LocalDate.parse(parts[DATE]),
                time = LocalTime.parse(parts[TIME]),
                fromFileSaved = parts.last().toBooleanStrict(),
            )
        } catch (_: DateTimeParseException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private const val SEPARATOR = "|"
    private const val PARTS = 4
    private const val DATE = 1
    private const val TIME = 2
}
