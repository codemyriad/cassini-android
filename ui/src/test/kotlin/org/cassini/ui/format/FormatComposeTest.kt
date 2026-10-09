package org.cassini.ui.format

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.cassini.ui.banner.BannerArgs
import org.cassini.ui.banner.BannerKind
import org.cassini.ui.banner.BannerSpec
import org.cassini.ui.banner.BannerText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone

/** The composable forms read the composition's resources and [LocalFormat], and give the pure forms' text. */
@RunWith(RobolectricTestRunner::class)
class FormatComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    @Config(qualifiers = "it")
    fun composableFormsFollowTheCompositionInItalian() {
        val seen = mutableListOf<String?>()
        compose.setContent {
            ProvideFixedFormat(Locale.ITALIAN, is24Hour = false) {
                seen += Durations.compact(192_000L)
                seen += Spoken.duration(2_893_000L)
                seen += Counts.plural(org.cassini.ui.R.plurals.ui_count_words, 4812)
                seen += Dates.dayTime(DayLabel.Yesterday, LocalTime.of(15, 10))
                seen += Sizes.storage(1_500_000_000L)
                seen += Progress.battery(BatteryUi.Projected(54, 41))
                seen += BannerText.title(BannerSpec(BannerKind.TX_QUEUE_HELD, BannerArgs(count = 2)))
                seen += BannerText.body(BannerSpec(BannerKind.TX_FAILED))
            }
        }
        compose.waitForIdle()
        assertEquals(
            listOf(
                "3 min 12 s",
                "48 minuti e 13 secondi",
                "4.812 parole",
                "Ieri, 3:10 PM",
                "1,4 GB",
                "54% → circa 41%",
                "Trascrizione in pausa · 2 in attesa",
                "La registrazione resta.",
            ),
            seen.take(8),
        )
    }

    @Test
    @Config(qualifiers = "en")
    fun composableFormsFollowTheCompositionInEnglish() {
        var text = ""
        compose.setContent {
            ProvideFixedFormat(Locale.ENGLISH) { text = Dates.dayTime(DayLabel.Today, LocalTime.of(10, 2)) }
        }
        compose.waitForIdle()
        assertEquals("Today, 10:02", text)
    }

    @Test
    @Config(qualifiers = "en")
    fun systemFormatFollowsTimeZoneAndTwentyFourHourChanges() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val defaultZone = TimeZone.getDefault()
        try {
            Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, "12")
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Rome"))
            var seen: FormatSettings? = null
            compose.setContent { ProvideSystemFormat { seen = LocalFormat.current } }
            compose.runOnIdle {
                assertEquals(false, seen?.is24Hour)
                assertEquals(ZoneId.of("Europe/Rome"), seen?.zone)
            }
            Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, "24")
            context.sendBroadcast(Intent(Intent.ACTION_TIME_CHANGED))
            compose.runOnIdle { assertEquals(true, seen?.is24Hour) }
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            context.sendBroadcast(Intent(Intent.ACTION_TIMEZONE_CHANGED))
            compose.runOnIdle { assertEquals(ZoneId.of("America/New_York"), seen?.zone) }
        } finally {
            TimeZone.setDefault(defaultZone)
        }
    }
}
