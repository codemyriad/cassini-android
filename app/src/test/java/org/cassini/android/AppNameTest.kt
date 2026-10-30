package org.cassini.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The product name (coordination decision D45): the launcher shows "Cassini talk", exactly so, in every language.
 * Sentences keep the short form "Cassini". The application ID is not part of the rename.
 */
@RunWith(RobolectricTestRunner::class)
class AppNameTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun theLauncherShowsCassiniTalk() {
        assertEquals("Cassini talk", context.applicationInfo.loadLabel(context.packageManager).toString())
        assertEquals("Open Cassini to resume.", context.getString(R.string.notif_paused_public))
    }

    @Test
    @Config(qualifiers = "it")
    fun theItalianLauncherShowsTheSameName() {
        assertEquals("Cassini talk", context.applicationInfo.loadLabel(context.packageManager).toString())
        assertEquals("Apri Cassini per riprendere.", context.getString(R.string.notif_paused_public))
    }

    @Test fun theApplicationIdStays() {
        assertEquals("org.cassini.android", context.packageName)
    }
}
