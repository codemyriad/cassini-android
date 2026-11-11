package io.codemyriad.cassini.backup

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import io.codemyriad.cassini.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 28, 35])
class BackupXmlSelectionTest {
    @Test fun compiledLegacyRulesSelectNoDataBefore28AndRequireD2dAfterward() {
        val context: Context = ApplicationProvider.getApplicationContext()
        var includes = 0
        var excludes = 0
        context.resources.getXml(R.xml.full_backup_content).use { xml ->
            while (xml.next() != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType != XmlPullParser.START_TAG) continue
                when (xml.name) {
                    "include" -> {
                        includes++
                        assertEquals("deviceToDeviceTransfer", xml.getAttributeValue(null, "requireFlags"))
                    }
                    "exclude" -> excludes++
                }
            }
        }
        assertEquals(if (Build.VERSION.SDK_INT >= 28) 4 else 0, includes)
        assertEquals(if (Build.VERSION.SDK_INT >= 28) 0 else 9, excludes)
    }
}
