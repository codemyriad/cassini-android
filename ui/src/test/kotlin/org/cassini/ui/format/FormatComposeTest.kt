package org.cassini.ui.format

import androidx.compose.ui.test.junit4.v2.createComposeRule
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
import java.util.Locale

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
}
