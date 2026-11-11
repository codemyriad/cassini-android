package io.codemyriad.cassini.note

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import io.codemyriad.cassini.ui.format.FormatSettings
import org.junit.Assert.assertEquals
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
