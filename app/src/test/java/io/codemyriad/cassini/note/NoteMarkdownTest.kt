package io.codemyriad.cassini.note

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import io.codemyriad.cassini.ui.format.FormatSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneId
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class NoteMarkdownTest {
    @Test fun resourcesAndNumericFormattingAreIndependentInTheActualExportCopy() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.FRENCH) }
        val resources = context.createConfigurationContext(config).resources
        val original = Locale.getDefault()
        val options = NoteMarkdown.options(
            resources,
            FormatSettings(Locale.US, false, ZoneId.of("Europe/Rome")),
            1793088000000,
            3120000,
        )
        assertEquals("Transcription", options.copy.transcriptHeading)
        assertEquals("Intervenants : {names}", options.copy.speakersLine)
        assertTrue(options.dateLine.startsWith("mardi 27 octobre 2026, 9:00 AM"))
        assertTrue(options.dateLine.endsWith(" · 52 min"))
        assertEquals(original, Locale.getDefault())
    }

    @Test fun durationUsesSharedMeetingRulesAcrossMinuteAndHourBoundaries() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.UK) }
        val resources = context.createConfigurationContext(config).resources
        val format = FormatSettings(Locale.UK, true, ZoneId.of("UTC"))
        val cases = mapOf(
            1L to "under 1 min",
            59_999L to "under 1 min",
            60_000L to "1 min",
            3_599_999L to "59 min",
            3_600_000L to "1 h 00 min",
            3_779_999L to "1 h 02 min",
            3_780_000L to "1 h 03 min",
            7_199_999L to "1 h 59 min",
            7_200_000L to "2 h 00 min",
        )
        for ((ms, expected) in cases) {
            assertEquals(
                "$ms",
                "Thursday 1 January 1970, 00:00 · $expected",
                NoteMarkdown.options(resources, format, 0, ms).dateLine,
            )
        }
        for (ms in listOf(0L, -1L)) {
            assertEquals("Thursday 1 January 1970, 00:00", NoteMarkdown.options(resources, format, 0, ms).dateLine)
        }
    }

    @Test fun durationCopyFollowsUiLanguageWhileDigitsFollowTheFormattingLocale() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (language in listOf("en", "it", "fr", "es", "pt-BR", "pt-PT")) {
            val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
            val resources = context.createConfigurationContext(config).resources
            val format = FormatSettings(Locale.forLanguageTag("ar-EG"), true, ZoneId.of("UTC"))
            val exactHour = NoteMarkdown.options(resources, format, 0, 3_600_000).dateLine
            val afterHour = NoteMarkdown.options(resources, format, 0, 3_780_000).dateLine
            assertTrue("$language: $exactHour", exactHour.endsWith(" · ١ h ٠٠ min"))
            assertTrue("$language: $afterHour", afterHour.endsWith(" · ١ h ٠٣ min"))
        }
    }

    @Test fun phoneClockChoiceOverridesTheNumericLocaleAndItsDefaultStaysIndependentOfTheUi() {
        assertFalse(NoteMarkdown.is24Hour(Locale.US, null))
        assertTrue(NoteMarkdown.is24Hour(Locale.UK, null))
        assertTrue(NoteMarkdown.is24Hour(Locale.US, "24"))
        assertFalse(NoteMarkdown.is24Hour(Locale.FRANCE, "12"))
    }

    @Test fun anUnsupportedPhoneLanguageUsesTheActualEnglishResourceFallbackForDateWords() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.GERMANY) }
        val resources = context.createConfigurationContext(config).resources
        val options = NoteMarkdown.options(
            resources,
            FormatSettings(Locale.GERMANY, true, ZoneId.of("Europe/Rome")),
            1793088000000,
            0,
        )
        assertEquals("Transcript", options.copy.transcriptHeading)
        assertEquals("Tuesday 27 October 2026, 09:00", options.dateLine)
    }

    @Test fun englishOverlapUsesTheSharedBritishListPattern() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.UK) }
        val resources = context.createConfigurationContext(config).resources
        val options = NoteMarkdown.options(resources, FormatSettings(Locale.UK, true, ZoneId.of("UTC")), 0, 0)
        assertEquals("(over {names})", options.copy.overLine)
        assertEquals("Ada, Ben and Chris", options.copy.speakerList(listOf("Ada", "Ben", "Chris")))
        assertTrue(options.dateLine.endsWith("00:00"))
    }
}
