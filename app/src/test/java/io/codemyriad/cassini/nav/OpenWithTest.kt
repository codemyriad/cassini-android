package io.codemyriad.cassini.nav

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.codemyriad.cassini.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * "Open with" (the parity review, gap 6): the activity takes ACTION_VIEW for every type the Library's picker
 * imports, so another app can hand over an MP3, an M4A or a WAV, not only an Ogg file; and ACTION_SEND of an audio
 * file from another app's share sheet.
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
        assertTrue("never a path (review of #45)", handlers("audio/mpeg", scheme = "file").isEmpty())
    }

    /** Receiving shares (approved after R10): an audio file shared from another app's share sheet. */
    @Test fun aSharedAudioFileOpensInTheApp() {
        fun shared(type: String): List<String> {
            val intent = Intent(Intent.ACTION_SEND).setType(type)
                .putExtra(Intent.EXTRA_STREAM, Uri.parse("content://chat/voice/7"))
                .addCategory(Intent.CATEGORY_DEFAULT)
                .setPackage(context.packageName)
            return context.packageManager.queryIntentActivities(intent, 0).map { it.activityInfo.name }
        }
        for (type in listOf("audio/mpeg", "audio/ogg", "audio/mp4", "application/ogg")) {
            assertEquals(type, listOf(MainActivity::class.java.name), shared(type))
        }
        assertTrue("shared text is not ours", shared("text/plain").isEmpty())
    }

    @Test fun otherTypesDoNot() {
        assertTrue(handlers("video/mp4").isEmpty())
        assertTrue(handlers("text/plain").isEmpty())
    }
}
