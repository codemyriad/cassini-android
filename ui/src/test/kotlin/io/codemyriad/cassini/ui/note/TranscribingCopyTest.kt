package io.codemyriad.cassini.ui.note

import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import io.codemyriad.cassini.ui.format.TimeLeftUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class TranscribingCopyTest {
    @Test fun compactEtaUsesTheCatalogSingularAndPluralInEveryLocale() {
        val expected = mapOf(
            "en" to ("about 1 min left" to "about 2 min left"),
            "fr" to ("environ 1 min restante" to "environ 2 min restantes"),
            "it" to ("circa 1 min rimanente" to "circa 2 min rimanenti"),
            "es" to ("queda 1 min" to "quedan unos 2 min"),
            "pt-BR" to ("cerca de 1 min restante" to "cerca de 2 min restantes"),
            "pt-PT" to ("cerca de 1 min restante" to "cerca de 2 min restantes"),
        )
        expected.forEach { (tag, copy) ->
            val locale = Locale.forLanguageTag(tag)
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val config = Configuration(context.resources.configuration).apply { setLocales(LocaleList(locale)) }
            val res = context.createConfigurationContext(config).resources
            assertEquals(tag, copy.first, transcribingEta(res, locale, TimeLeftUi.Range(60_000, 60_000)))
            assertEquals(tag, copy.second, transcribingEta(res, locale, TimeLeftUi.Range(120_000, 120_000)))
            val range = transcribingEta(res, locale, TimeLeftUi.Range(360_000, 540_000))
            assertEquals(tag, copy.second.replace("2", "6–9"), range)
            for (time in listOf(TimeLeftUi.Calculating, TimeLeftUi.Finishing, TimeLeftUi.Range(0, 30_000))) {
                assertFalse(tag, transcribingEta(res, locale, time).first().isUpperCase())
            }
        }
    }
}
