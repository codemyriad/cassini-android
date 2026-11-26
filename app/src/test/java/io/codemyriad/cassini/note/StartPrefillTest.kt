package io.codemyriad.cassini.note

import io.codemyriad.cassini.core.library.AudioKind
import io.codemyriad.cassini.core.library.Origin
import io.codemyriad.cassini.data.db.NoteEntity
import io.codemyriad.cassini.ui.note.SetDateTimeUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** The prefill of "When was this recorded?" (D112, L-87, D-1164). */
class StartPrefillTest {
    private val rome = ZoneId.of("Europe/Rome")
    private val now = LocalDateTime.of(2026, 11, 25, 21, 47, 33).atZone(rome).toInstant()

    private fun imported(saved: Long? = null, recordedAt: String? = null, startedAt: String? = null) = NoteEntity(
        id = "n",
        title = "REC001",
        createdAt = 1,
        addedAt = 1,
        origin = Origin.IMPORTED_AUDIO,
        audioFile = "source.opus",
        audioKind = AudioKind.PLAIN,
        durationMs = 30 * 60_000L,
        updatedAt = 1,
        recordedAt = recordedAt,
        sourceModifiedAt = saved,
        startedAt = startedAt,
    )

    @Test fun aKnownStartComesFirst() {
        val note = imported(saved = now.toEpochMilli(), recordedAt = "recordedAtUtc=2026-11-20T09:05:41Z")
        assertEquals(
            SetDateTimeUi("n", LocalDate.of(2026, 11, 20), LocalTime.of(10, 5)),
            StartPrefill.of(note, now, rome)
        )
        val set = imported(recordedAt = "recordedAtUtc=2026-11-20T09:05:41Z", startedAt = "2026-11-19T08:00:00-05:00")
        assertEquals(
            SetDateTimeUi("n", LocalDate.of(2026, 11, 19), LocalTime.of(14, 0)),
            StartPrefill.of(set, now, rome)
        )
    }

    @Test fun aLocalLabelIsShownAsWritten() {
        val note = imported(recordedAt = "recordedAtLocal=2026-11-20T18:30:00")
        assertEquals(
            SetDateTimeUi("n", LocalDate.of(2026, 11, 20), LocalTime.of(18, 30)),
            StartPrefill.of(note, now, rome)
        )
    }

    @Test fun otherwiseTheFilesSavedTimeMinusItsLength() {
        val saved = LocalDateTime.of(2026, 11, 24, 10, 0).atZone(rome).toInstant().toEpochMilli()
        assertEquals(
            SetDateTimeUi("n", LocalDate.of(2026, 11, 24), LocalTime.of(9, 30), fromFileSaved = true),
            StartPrefill.of(imported(saved = saved), now, rome),
        )
    }

    @Test fun otherwiseTodayAtTheCurrentTimeWithNoLine() {
        assertEquals(
            SetDateTimeUi("n", LocalDate.of(2026, 11, 25), LocalTime.of(21, 47)),
            StartPrefill.of(imported(), now, rome)
        )
    }

    @Test fun saveStoresTheZonesOffsetOnTheChosenDay() {
        assertEquals(
            "2026-11-23T14:36:00+01:00",
            StartPrefill.startedAt(LocalDate.of(2026, 11, 23), LocalTime.of(14, 36), rome)
        )
        assertEquals(
            "2026-07-23T14:36:00+02:00",
            StartPrefill.startedAt(LocalDate.of(2026, 7, 23), LocalTime.of(14, 36), rome)
        )
        assertEquals(
            "2026-07-23T14:36:00+00:00",
            StartPrefill.startedAt(LocalDate.of(2026, 7, 23), LocalTime.of(14, 36), ZoneId.of("UTC"))
        )
    }

    @Test fun theSavedSheetRoundTrips() {
        val ui = SetDateTimeUi("a-b|c", LocalDate.of(2026, 1, 2), LocalTime.of(3, 4), fromFileSaved = true)
        assertNull("a separator in the id cannot be read back", StartPrefill.decode(StartPrefill.encode(ui)))
        val plain = ui.copy(noteId = "0b6f6a7e-1c2d-4e5f-8a9b-0c1d2e3f4a5b")
        assertEquals(plain, StartPrefill.decode(StartPrefill.encode(plain)))
        assertNull(StartPrefill.decode("rename"))
        assertNull(StartPrefill.decode(null))
    }

    @Test fun anInstantPrefillIgnoresTheSecondsOfTheClock() {
        assertEquals(
            LocalTime.of(21, 47),
            StartPrefill.of(imported(), Instant.parse("2026-11-25T20:47:59.999Z"), rome).time
        )
    }
}
