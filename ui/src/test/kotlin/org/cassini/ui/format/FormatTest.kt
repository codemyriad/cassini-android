package org.cassini.ui.format

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import org.cassini.ui.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

private const val S = 1_000L
private const val MIN = 60_000L
private const val H = 3_600_000L
private const val MIB = 1_048_576L

/** The pinned model sizes of ModelCatalog (0.1.0 pins): 640, 62 and 28 MB, 730 MB in all, 861 MiB needed free. */
internal object ModelBytes {
    const val SPEECH = 652_184_281L + 11_845_275L + 6_355_277L + 93_939L
    const val SPEAKERS = 64_982_914L
    const val VOICES = 29_596_978L
    const val REQUIRED_FREE = 861 * MIB
}

private val MONDAY = LocalDate.of(2026, 10, 5)

/** Formatting in English (docs/design/features.md section 0), with the resources Robolectric loads for `en`. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en")
class FormatTest {
    private val res: Resources = ApplicationProvider.getApplicationContext<Context>().resources
    private val f = FormatSettings(Locale.ENGLISH, is24Hour = true, zone = FIXED_ZONE)
    private val f12 = f.copy(is24Hour = false)

    @Test fun clock() {
        assertEquals("02:41", Durations.clock(161 * S))
        assertEquals("48:13", Durations.clock(48 * MIN + 13 * S))
        assertEquals("59:59", Durations.clock(H - 1))
        assertEquals("1:12:05", Durations.clock(H + 12 * MIN + 5 * S))
        assertEquals("00:00", Durations.clock(0))
        assertEquals("00:00", Durations.clock(-5 * S))
        assertEquals("00:01", Durations.clock(1_999))
        assertEquals("29:00–29:45", Durations.clockRange(res, 29 * MIN, 29 * MIN + 45 * S))
        assertEquals("29:52 / 48:13", Durations.processed(res, 29 * MIN + 52 * S, 48 * MIN + 13 * S))
    }

    @Test fun proseDurations() {
        assertEquals("3 min 12 s", Durations.compact(res, 3 * MIN + 12 * S))
        assertEquals("45 s", Durations.compact(res, 45 * S))
        assertEquals("3 min", Durations.compact(res, 3 * MIN))
        assertEquals("1 h 12 min", Durations.compact(res, H + 12 * MIN + 5 * S))
        assertEquals("1 h", Durations.compact(res, H + 30 * S))
        assertEquals("Speaks for 3 min 12 s in this note", Durations.speaksFor(res, 3 * MIN + 12 * S))
        assertEquals("22 min confirmed speech", Durations.confirmedSpeech(res, 22 * MIN + 59 * S))
        assertEquals("under 1 min confirmed speech", Durations.confirmedSpeech(res, 59 * S))
        assertEquals("1 h 5 min confirmed speech", Durations.confirmedSpeech(res, 65 * MIN))
        assertEquals("2 h confirmed speech", Durations.confirmedSpeech(res, 2 * H))
        assertEquals("10 min", Durations.atLeastOneMinute(res, 10 * MIN + 40 * S))
        assertEquals("1 min", Durations.atLeastOneMinute(res, 20 * S))
    }

    @Test fun recordingHintRounding() {
        assertEquals("31 h", Durations.recordingLeft(res, 31 * H + 50 * MIN))
        assertEquals("2 h", Durations.recordingLeft(res, 2 * H))
        assertEquals("1 h 55 min", Durations.recordingLeft(res, 2 * H - 1))
        assertEquals("1 h 35 min", Durations.recordingLeft(res, H + 39 * MIN))
        assertEquals("35 min", Durations.recordingLeft(res, 37 * MIN))
        assertEquals("5 min", Durations.recordingLeft(res, 3 * MIN))
        assertEquals(
            "Keeps recording with the screen off or in another app. Space for about 31 h more.",
            Durations.spaceHint(res, 31 * H + 10 * MIN),
        )
        assertEquals(
            "Keeps recording with the screen off or in another app. Stops at 2 hours, in about 1 h 35 min.",
            Durations.capHint(res, H + 36 * MIN),
        )
    }

    @Test fun spoken() {
        assertEquals("48 minutes 13 seconds", Spoken.duration(res, 48 * MIN + 13 * S, f))
        assertEquals("24 minutes 7 seconds", Spoken.duration(res, 24 * MIN + 7 * S, f))
        assertEquals("1 hour 12 minutes 5 seconds", Spoken.duration(res, H + 12 * MIN + 5 * S, f))
        assertEquals("2 hours", Spoken.duration(res, 2 * H, f))
        assertEquals("1 hour 5 seconds", Spoken.duration(res, H + 5 * S, f))
        assertEquals("1 second", Spoken.duration(res, 1 * S, f))
        assertEquals("1 minute", Spoken.duration(res, MIN, f))
        assertEquals("0 seconds", Spoken.duration(res, 0, f))
        assertEquals("today 10:02", Spoken.dayTime(res, DayLabel.Today, LocalTime.of(10, 2), f))
        assertEquals(
            "Weekly product sync, 48 minutes 13 seconds, today 10:02, transcribing",
            Spoken.noteRow(
                res,
                "Weekly product sync",
                48 * MIN + 13 * S,
                DayLabel.Today,
                LocalTime.of(10, 2),
                "Transcribing",
                f,
            ),
        )
        assertEquals(
            "Giulia, named automatically, at 11 minutes 41 seconds",
            Spoken.turn(res, "Giulia", 11 * MIN + 41 * S, auto = true, format = f),
        )
        assertEquals(
            "Speaker 3, at 12 minutes 20 seconds",
            Spoken.turn(res, "Speaker 3", 12 * MIN + 20 * S, auto = false, format = f),
        )
        assertEquals("11:44 of 23:50", Spoken.position(res, 11 * MIN + 44 * S, 23 * MIN + 50 * S))
    }

    @Test fun countsAndPlurals() {
        assertEquals("4,812", Counts.number(4812, f))
        assertEquals("1,234,567", Counts.number(1_234_567, f))
        assertEquals("1 word", Counts.words(res, 1, f))
        assertEquals("4,812 words", Counts.words(res, 4812, f))
        assertEquals("0 speakers", Counts.speakers(res, 0, f))
        assertEquals("1 speaker", Counts.speakers(res, 1, f))
        assertEquals("3 speakers", Counts.speakers(res, 3, f))
        assertEquals("1 note", Counts.notes(res, 1, f))
        assertEquals("14 notes", Counts.notes(res, 14, f))
        assertEquals("1 person", Counts.people(res, 1, f))
        assertEquals("4 people", Counts.people(res, 4, f))
        assertEquals("2 versions", Counts.versions(res, 2, f))
        assertEquals("1 day left", Counts.daysLeft(res, 1, f))
        assertEquals("23 days left", Counts.daysLeft(res, 23, f))
        assertEquals("1 more in queue", Counts.moreInQueue(res, 1, f))
        assertEquals("2 ahead of this note.", Counts.aheadOfNote(res, 2, f))
        assertEquals("1 name from saved voices", Counts.namesFromVoices(res, 1, f))
        assertEquals("2 names from saved voices", Counts.namesFromVoices(res, 2, f))
        assertEquals("Delete 1 note for good?", Counts.plural(res, R.plurals.ui_trash_delete_all_title, 1, f))
        assertEquals("Delete 3 notes for good?", Counts.plural(res, R.plurals.ui_trash_delete_all_title, 3, f))
        assertEquals("· 3 speakers", Counts.plural(res, R.plurals.ui_status_suffix_speakers, 3, f))
        assertEquals("62%", Counts.percent(res, 62))
        assertEquals("11×", Counts.speed(res, 11.3, f))
        assertEquals("2.5×", Counts.speed(res, 2.46, f))
        assertEquals("Opus 48 kHz", Counts.captureFormat(res, m4a = false, sampleRateHz = 48_000, format = f))
        assertEquals("M4A 44.1 kHz", Counts.captureFormat(res, m4a = true, sampleRateHz = 44_100, format = f))
        assertEquals("Speaker 3", Counts.speakerLabel(res, 3))
    }

    @Test fun sizes() {
        assertEquals(640L, Sizes.mebibytes(ModelBytes.SPEECH))
        assertEquals(62L, Sizes.mebibytes(ModelBytes.SPEAKERS))
        assertEquals(28L, Sizes.mebibytes(ModelBytes.VOICES))
        assertEquals(1L, Sizes.mebibytes(0))
        assertEquals("640 MB", Sizes.megabytes(res, ModelBytes.SPEECH, f))
        val parts = listOf(ModelBytes.SPEECH, ModelBytes.SPEAKERS, ModelBytes.VOICES)
        assertEquals("730 MB", Sizes.totalMegabytes(res, parts, f))
        assertEquals("730 MB", Sizes.megabytes(res, parts.sum(), f))
        assertEquals("90 MB", Sizes.totalMegabytes(res, listOf(ModelBytes.SPEAKERS, ModelBytes.VOICES), f))
        assertEquals("860 MB", Sizes.megabytes(res, ModelBytes.REQUIRED_FREE, f))
        assertEquals("860 MB", Sizes.storage(res, ModelBytes.REQUIRED_FREE, f))
        assertEquals("1.4 GB", Sizes.storage(res, 1_500_000_000L, f))
        assertEquals("1,240 MB", Sizes.megabytes(res, 1_240 * MIB, f))
        assertEquals("296 of 640 MB.", Sizes.downloaded(res, 296 * MIB + 9_999, ModelBytes.SPEECH, f))
        assertEquals("640 of 640 MB.", Sizes.downloaded(res, ModelBytes.SPEECH + MIB * 4, ModelBytes.SPEECH, f))
    }

    @Test fun dates() {
        assertEquals("Today", Dates.dayLabel(res, DayLabel.Today, f))
        assertEquals("Yesterday", Dates.dayLabel(res, DayLabel.Yesterday, f))
        assertEquals("Monday 5 Oct", Dates.dayLabel(res, DayLabel.Date(MONDAY, withYear = false), f))
        assertEquals("5 Oct 2025", Dates.dayLabel(res, DayLabel.Date(MONDAY.minusYears(1), withYear = true), f))
        assertEquals("Today, 10:02", Dates.dayTime(res, DayLabel.Today, LocalTime.of(10, 2), f))
        assertEquals("Yesterday, 15:10", Dates.dayTime(res, DayLabel.Yesterday, LocalTime.of(15, 10), f))
        val thursday = MONDAY.plusDays(3)
        assertEquals("8 Oct, 15:40", Dates.shortDateTime(res, thursday, LocalTime.of(15, 40), false, f))
        assertEquals("8 Oct 2026, 15:10", Dates.longDateTime(res, MONDAY.plusDays(3), LocalTime.of(15, 10), f))
        assertEquals("Deleted 2 Oct", Dates.deleted(res, DayLabel.Date(MONDAY.minusDays(3), withYear = false), f))
        assertEquals("Deleted yesterday", Dates.deleted(res, DayLabel.Yesterday, f))
        assertEquals(
            "Deleted 2 Oct · 23 days left",
            Dates.trashLine(res, DayLabel.Date(MONDAY.minusDays(3), withYear = false), 23, f),
        )
        assertEquals("Deleted today · 30 days left", Dates.trashLine(res, DayLabel.Today, 30, f))
        assertEquals("Voice note 8 Oct, 08:14", Dates.voiceNoteTitle(res, MONDAY.plusDays(3), LocalTime.of(8, 14), f))
    }

    @Test fun twelveAndTwentyFourHours() {
        assertEquals("08:14", Dates.time(LocalTime.of(8, 14), f))
        assertEquals("15:10", Dates.time(LocalTime.of(15, 10), f))
        assertEquals("8:14 AM", Dates.time(LocalTime.of(8, 14), f12))
        assertEquals("3:10 PM", Dates.time(LocalTime.of(15, 10), f12))
        assertEquals("12:05 AM", Dates.time(LocalTime.of(0, 5), f12))
        assertEquals("Yesterday, 3:10 PM", Dates.dayTime(res, DayLabel.Yesterday, LocalTime.of(15, 10), f12))
        assertEquals("today 10:02 AM", Spoken.dayTime(res, DayLabel.Today, LocalTime.of(10, 2), f12))
    }

    @Test fun progress() {
        assertEquals("6–9 min", Progress.timeLeft(res, TimeLeftUi.Range(6 * MIN, 9 * MIN)))
        assertEquals("6–9 min", Progress.timeLeft(res, TimeLeftUi.Range(6 * MIN + 50 * S, 8 * MIN + 10 * S)))
        assertEquals("About 6 min", Progress.timeLeft(res, TimeLeftUi.Range(6 * MIN, 6 * MIN)))
        assertEquals("Under 1 min", Progress.timeLeft(res, TimeLeftUi.Range(10 * S, 50 * S)))
        assertEquals("1–2 min", Progress.timeLeft(res, TimeLeftUi.Range(10 * S, 2 * MIN)))
        assertEquals("1 h 10 min–1 h 30 min", Progress.timeLeft(res, TimeLeftUi.Range(70 * MIN, 90 * MIN)))
        assertEquals("45 min–1 h 10 min", Progress.timeLeft(res, TimeLeftUi.Range(45 * MIN, 70 * MIN)))
        assertEquals("Calculating…", Progress.timeLeft(res, TimeLeftUi.Calculating))
        assertEquals("Finishing…", Progress.timeLeft(res, TimeLeftUi.Finishing))
        assertEquals("About 6–9 min left", Progress.timeLeftLine(res, TimeLeftUi.Range(6 * MIN, 9 * MIN)))
        assertEquals("About 6 min left", Progress.timeLeftLine(res, TimeLeftUi.Range(6 * MIN, 6 * MIN)))
        assertEquals("Under 1 min left", Progress.timeLeftLine(res, TimeLeftUi.Range(0, 30 * S)))
        assertEquals("Calculating time left…", Progress.timeLeftLine(res, TimeLeftUi.Calculating))
        assertEquals("54% → about 41%", Progress.battery(res, BatteryUi.Projected(54, 41)))
        assertEquals("Plugged in", Progress.battery(res, BatteryUi.PluggedIn(80)))
        assertEquals("Measuring…", Progress.battery(res, BatteryUi.Measuring(80)))
        assertNull(Progress.battery(res, BatteryUi.Hidden))
        assertEquals("Finding words and speakers", Progress.headline(res, ProgressHeadline.FINDING_WORDS_AND_SPEAKERS))
        assertEquals("Finding words", Progress.headline(res, ProgressHeadline.FINDING_WORDS))
        assertEquals("Words & voices", Progress.stage(res, StageUi.WORDS_AND_VOICES))
        assertEquals("Step 3 of 5: Words and voices", Progress.stageDescription(res, StageUi.WORDS_AND_VOICES))
        assertEquals("skipped", Progress.stageState(res, StageState.SKIPPED))
        assertEquals("Downloading speech recognition — 46%", ModelPartUi.SPEECH.downloading(res, 46))
        assertEquals("Telling speakers apart", ModelPartUi.SPEAKERS.label(res))
    }
}

/** The same formatters in Italian: plurals, grouping, decimal comma, month names and the "e" of spoken durations. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "it")
class FormatItalianTest {
    private val res: Resources = ApplicationProvider.getApplicationContext<Context>().resources
    private val f = FormatSettings(Locale.ITALIAN, is24Hour = true, zone = FIXED_ZONE)
    private val f12 = f.copy(is24Hour = false)

    @Test fun durations() {
        assertEquals("02:41", Durations.clock(161 * S))
        assertEquals("3 min 12 s", Durations.compact(res, 3 * MIN + 12 * S))
        assertEquals("Parla per 3 min 12 s in questa nota", Durations.speaksFor(res, 3 * MIN + 12 * S))
        assertEquals("meno di 1 min di parlato confermato", Durations.confirmedSpeech(res, 30 * S))
        assertEquals("1 h 5 min di parlato confermato", Durations.confirmedSpeech(res, 65 * MIN))
        assertEquals("48 minuti e 13 secondi", Spoken.duration(res, 48 * MIN + 13 * S, f))
        assertEquals("1 ora, 12 minuti e 5 secondi", Spoken.duration(res, H + 12 * MIN + 5 * S, f))
        assertEquals("1 minuto", Spoken.duration(res, MIN, f))
        assertEquals("0 secondi", Spoken.duration(res, 0, f))
        assertEquals(
            "Continua a registrare a schermo spento o in un’altra app. Spazio per circa 31 h ancora.",
            Durations.spaceHint(res, 31 * H),
        )
        assertEquals("11:44 di 23:50", Spoken.position(res, 11 * MIN + 44 * S, 23 * MIN + 50 * S))
    }

    @Test fun countsAndPlurals() {
        assertEquals("4.812", Counts.number(4812, f))
        assertEquals("1 parola", Counts.words(res, 1, f))
        assertEquals("4.812 parole", Counts.words(res, 4812, f))
        assertEquals("1 voce", Counts.speakers(res, 1, f))
        assertEquals("3 voci", Counts.speakers(res, 3, f))
        assertEquals("1 nota", Counts.notes(res, 1, f))
        assertEquals("14 note", Counts.notes(res, 14, f))
        assertEquals("1 persona", Counts.people(res, 1, f))
        assertEquals("4 persone", Counts.people(res, 4, f))
        assertEquals("1 giorno rimasto", Counts.daysLeft(res, 1, f))
        assertEquals("23 giorni rimasti", Counts.daysLeft(res, 23, f))
        assertEquals("1 altra in coda", Counts.moreInQueue(res, 1, f))
        assertEquals("altre 2 in coda", Counts.moreInQueue(res, 2, f))
        assertEquals("2 note prima di questa.", Counts.aheadOfNote(res, 2, f))
        assertEquals("Eliminare 3 note per sempre?", Counts.plural(res, R.plurals.ui_trash_delete_all_title, 3, f))
        assertEquals("2,5×", Counts.speed(res, 2.46, f))
        assertEquals("M4A 44,1 kHz", Counts.captureFormat(res, m4a = true, sampleRateHz = 44_100, format = f))
        assertEquals("Voce 3", Counts.speakerLabel(res, 3))
    }

    @Test fun sizes() {
        val parts = listOf(ModelBytes.SPEECH, ModelBytes.SPEAKERS, ModelBytes.VOICES)
        assertEquals("730 MB", Sizes.totalMegabytes(res, parts, f))
        assertEquals("1.240 MB", Sizes.megabytes(res, 1_240 * MIB, f))
        assertEquals("1,4 GB", Sizes.storage(res, 1_500_000_000L, f))
        assertEquals("296 di 640 MB.", Sizes.downloaded(res, 296 * MIB, ModelBytes.SPEECH, f))
    }

    @Test fun dates() {
        assertEquals("Oggi", Dates.dayLabel(res, DayLabel.Today, f))
        assertEquals("Ieri", Dates.dayLabel(res, DayLabel.Yesterday, f))
        assertEquals("Lunedì 5 ott", Dates.dayLabel(res, DayLabel.Date(MONDAY, withYear = false), f))
        assertEquals("5 ott 2025", Dates.dayLabel(res, DayLabel.Date(MONDAY.minusYears(1), withYear = true), f))
        assertEquals("Oggi, 10:02", Dates.dayTime(res, DayLabel.Today, LocalTime.of(10, 2), f))
        assertEquals("Nota vocale 8 ott, 08:14", Dates.voiceNoteTitle(res, MONDAY.plusDays(3), LocalTime.of(8, 14), f))
        assertEquals(
            "Eliminata 2 ott · 23 giorni rimasti",
            Dates.trashLine(res, DayLabel.Date(MONDAY.minusDays(3), withYear = false), 23, f),
        )
        assertEquals("Eliminata oggi · 30 giorni rimasti", Dates.trashLine(res, DayLabel.Today, 30, f))
        assertEquals("oggi alle 10:02", Spoken.dayTime(res, DayLabel.Today, LocalTime.of(10, 2), f))
        assertEquals("15:10", Dates.time(LocalTime.of(15, 10), f))
        assertEquals("3:10 PM", Dates.time(LocalTime.of(15, 10), f12))
    }

    @Test fun progress() {
        assertEquals("6–9 min", Progress.timeLeft(res, TimeLeftUi.Range(6 * MIN, 9 * MIN)))
        assertEquals("Circa 6 min", Progress.timeLeft(res, TimeLeftUi.Range(6 * MIN, 6 * MIN)))
        assertEquals("Meno di 1 min", Progress.timeLeft(res, TimeLeftUi.Range(0, 30 * S)))
        assertEquals("Circa 6–9 min rimanenti", Progress.timeLeftLine(res, TimeLeftUi.Range(6 * MIN, 9 * MIN)))
        assertEquals("54% → circa 41%", Progress.battery(res, BatteryUi.Projected(54, 41)))
        assertEquals("Passo 3 di 5: Parole e voci", Progress.stageDescription(res, StageUi.WORDS_AND_VOICES))
        assertEquals("Ricerca di parole e voci", Progress.headline(res, ProgressHeadline.FINDING_WORDS_AND_SPEAKERS))
        assertEquals("Download del riconoscimento vocale — 46%", ModelPartUi.SPEECH.downloading(res, 46))
    }
}
