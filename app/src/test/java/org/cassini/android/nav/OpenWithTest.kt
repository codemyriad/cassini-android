package org.cassini.android.nav

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.cassini.android.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * "Open with" (the parity review, gap 6): the activity takes ACTION_VIEW for every type the Library's picker
 * imports, so another app can hand over an MP3, an M4A or a WAV, not only an Ogg file.
 */
@RunWith(RobolectricTestRunner::class)
class OpenWithTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun handlers(type: String, scheme: String = "content"): List<String> {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("$scheme://files/recording"), type)
            .addCategory(Intent.CATEGORY_DEFAULT)
            .setPackage(context.packageName)
        return context.packageManager.queryIntentActivities(intent, 0).map { it.activityInfo.name }
    }

    @Test fun everyAudioTypeOpensInTheApp() {
        val types = listOf(
            "audio/ogg",
            "audio/opus",
            "application/ogg",
            "audio/mpeg",
            "audio/mp4",
            "audio/x-wav",
            "audio/flac",
        )
        for (type in types) assertEquals(type, listOf(MainActivity::class.java.name), handlers(type))
        assertEquals("a file uri too", listOf(MainActivity::class.java.name), handlers("audio/mpeg", scheme = "file"))
    }

    @Test fun otherTypesDoNot() {
        assertTrue(handlers("video/mp4").isEmpty())
        assertTrue(handlers("text/plain").isEmpty())
    }
}
