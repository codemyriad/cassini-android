package io.codemyriad.cassini

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The product name (coordination decision D45): the Play release launcher shows "Cassini talk", in every language,
 * while debug builds show "Cassini debug" under `io.codemyriad.cassini.talk.debug` so device checks install beside the
 * user's app. Sentences keep the short form "Cassini".
 */
@RunWith(RobolectricTestRunner::class)
class AppNameTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val expectedLauncherLabel: String = if (BuildConfig.DEBUG) "Cassini debug" else "Cassini talk"
    private val expectedApplicationId: String =
        if (BuildConfig.DEBUG) "io.codemyriad.cassini.talk.debug" else "io.codemyriad.cassini.talk"

    @Test fun theLauncherLabelMatchesTheBuildType() {
        assertEquals(expectedLauncherLabel, context.applicationInfo.loadLabel(context.packageManager).toString())
        assertEquals("Cassini talk", context.getString(R.string.app_name))
        assertEquals("Open Cassini to resume.", context.getString(R.string.notif_paused_public))
        val mainManifest = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml")).documentElement
        val application = mainManifest.getElementsByTagName("application").item(0) as Element
        assertEquals("@string/app_name", application.getAttribute("android:label"))
    }

    @Test
    @Config(qualifiers = "it")
    fun theItalianLauncherShowsTheSameName() {
        assertEquals(expectedLauncherLabel, context.applicationInfo.loadLabel(context.packageManager).toString())
        assertEquals("Cassini talk", context.getString(R.string.app_name))
        assertEquals("Apri Cassini per riprendere.", context.getString(R.string.notif_paused_public))
    }

    @Test fun theApplicationIdMatchesTheBuildType() {
        assertEquals(expectedApplicationId, BuildConfig.APPLICATION_ID)
        assertEquals(expectedApplicationId, context.packageName)
    }
}
