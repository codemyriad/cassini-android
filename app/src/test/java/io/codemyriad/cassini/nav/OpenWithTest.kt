package io.codemyriad.cassini.nav

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.codemyriad.cassini.MainActivity
import io.codemyriad.cassini.core.audio.ImportTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * "Open with" (the parity review, gap 6): the activity takes ACTION_VIEW for every decided type of the shared
 * import-types contract (L-25, [ImportTypes]), so another app can hand over an MP3, an M4A or a WAV, not only an Ogg
 * file; and ACTION_SEND of such a file from another app's share sheet. Open types (FLAC), compatibility types and
 * other audio are not offered.
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

    @Test fun everyDecidedAudioTypeOpensInTheApp() {
        val types = listOf(
            "audio/ogg",
            "audio/opus",
            "audio/mpeg",
            "audio/mp4",
            "audio/x-m4a",
            "audio/wav",
            "audio/x-wav"
        )
        assertEquals(types.toSet(), types.toSet() intersect ImportTypes.ANDROID_MIME_TYPES.toSet())
        for (type in ImportTypes.ANDROID_MIME_TYPES) {
            assertEquals(type, listOf(MainActivity::class.java.name), handlers(type))
        }
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
        for (type in ImportTypes.ANDROID_MIME_TYPES) {
            assertEquals(type, listOf(MainActivity::class.java.name), shared(type))
        }
        assertTrue("shared text is not ours", shared("text/plain").isEmpty())
        assertTrue("FLAC is still open (L-09, D-938)", shared("audio/flac").isEmpty())
    }

    @Test fun otherTypesDoNot() {
        for (type in listOf(
            "video/mp4",
            "video/ogg",
            "text/plain",
            "audio/flac",
            "audio/x-flac",
            "audio/aac",
            "application/ogg"
        )) {
            assertTrue(type, handlers(type).isEmpty())
        }
    }
}
