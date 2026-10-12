package org.cassini.android.adapters

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The app's part of a job (architecture 5.7): the memory preflight and the placeholder label. */
@RunWith(RobolectricTestRunner::class)
class InstalledEnginesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun aLongClipThatDoesNotFitTheHeapGivesTheLowMemoryHint() {
        val hour = 60 * 60 * 1000L
        // One hour of 16 kHz float PCM plus 30% is about 300 MB.
        assertTrue(InstalledEngines.lowMemory(context, hour, availableBytes = 100L shl 20))
        assertFalse(InstalledEngines.lowMemory(context, hour, availableBytes = 400L shl 20))
        assertFalse("an unknown duration adds no hint", InstalledEngines.lowMemory(context, null, availableBytes = 0))
        assertTrue(InstalledEngines.environment(context).lowMemory(Long.MAX_VALUE / 100_000))
    }

    @Test fun thePlaceholderLabelUsesTheAppStrings() {
        assertEquals("Speaker 2", InstalledEngines.environment(context).placeholderLabel(2))
    }
}
