package org.cassini.ui.format

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.util.Locale

/**
 * Dates and sizes in the six app languages (D50 point 5): each language gets the CLDR pattern of its locale, so
 * Portuguese reads "5 de out." and European Portuguese "5/10"; English keeps the boards' "Monday 5 Oct" in every
 * region, and the Italian patterns are the boards' Italian ones. French writes sizes in Mo and Go.
 */
@RunWith(RobolectricTestRunner::class)
class FormatLocalesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun resources(tag: String): Resources {
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(tag))
        }
        return context.createConfigurationContext(configuration).resources
    }

    private fun format(tag: String) = FormatSettings(Locale.forLanguageTag(tag), is24Hour = true, zone = FIXED_ZONE)

    @Test fun daysFollowTheLocale() {
        // Language tag to: the day header in the current year, with a year, and the short date.
        val expected = mapOf(
            "en" to listOf("Monday 5 Oct", "5 Oct 2026", "5 Oct"),
            "en-US" to listOf("Monday 5 Oct", "5 Oct 2026", "5 Oct"),
            "it" to listOf("Lunedì 5 ott", "5 ott 2026", "5 ott"),
            "fr" to listOf("Lundi 5 oct.", "5 oct. 2026", "5 oct."),
            "es" to listOf("Lunes, 5 oct", "5 oct 2026", "5 oct"),
            "pt-BR" to listOf("Segunda-feira, 5 de out.", "5 de out. de 2026", "5 de out."),
            "pt-PT" to listOf("Segunda-feira, 5/10", "5/10/2026", "5/10"),
        )
        for ((tag, labels) in expected) {
            val res = resources(tag)
            val f = format(tag)
            val shown = listOf(
                Dates.dayLabel(res, DayLabel.Date(MONDAY, withYear = false), f),
                Dates.dayLabel(res, DayLabel.Date(MONDAY, withYear = true), f),
                Dates.shortDate(MONDAY, withYear = false, format = f),
            )
            assertEquals(tag, labels, shown)
        }
    }

    @Test fun sizesUseTheLanguageUnits() {
        val expected = mapOf(
            "en" to listOf("640 MB", "1.4 GB"),
            "it" to listOf("640 MB", "1,4 GB"),
            "fr" to listOf("640 Mo", "1,4 Go"),
            "es" to listOf("640 MB", "1,4 GB"),
            "pt-BR" to listOf("640 MB", "1,4 GB"),
            "pt-PT" to listOf("640 MB", "1,4 GB"),
        )
        for ((tag, sizes) in expected) {
            val res = resources(tag)
            val f = format(tag)
            assertEquals(tag, sizes, listOf(Sizes.megabytes(res, ModelBytes.SPEECH, f), Sizes.storage(res, GIB_1_4, f)))
        }
    }

    private companion object {
        val MONDAY: LocalDate = LocalDate.of(2026, 10, 5)
        const val GIB_1_4 = 1_503_238_553L
    }
}
