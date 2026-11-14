package io.codemyriad.cassini.backup

import io.codemyriad.cassini.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Source manifest ceiling plus runtime agent; default system traversal must never back up models or settings. */
class BackupManifestConfigurationTest {
    private val root = File(checkNotNull(System.getProperty("cassini.fixtures"))).parentFile

    private fun xml(path: String): Element = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(File(root, path)).documentElement

    @Test fun theGuardedAgentIsEnabledWithoutForegroundOrKeyValueBackup() {
        val application = xml("app/src/main/AndroidManifest.xml").getElementsByTagName("application").item(0) as Element
        val namespace = "http://schemas.android.com/apk/res/android"
        fun attribute(name: String) = application.getAttributeNS(namespace, name)
        assertEquals("true", attribute("allowBackup"))
        assertEquals("true", attribute("fullBackupOnly"))
        assertEquals("false", attribute("backupInForeground"))
        assertEquals("true", attribute("killAfterRestore"))
        assertEquals("false", attribute("restoreAnyVersion"))
        assertEquals(".backup.CassiniBackupAgent", attribute("backupAgent"))
        assertEquals("@xml/full_backup_content", attribute("fullBackupContent"))
        assertEquals("@xml/data_extraction_rules", attribute("dataExtractionRules"))
    }

    @Test fun pre28ExcludesEveryDomainAndLaterRulesShareOneLibraryCeiling() {
        assertTrue(BuildConfig.CLOUD_BACKUP_ENABLED)
        checkExcludes(xml("app/src/main/res/xml/full_backup_content.xml"))
        val modern = xml("app/src/main/res/xml/data_extraction_rules.xml")
        val cloud = modern.getElementsByTagName("cloud-backup").item(0) as Element
        // Cloud is skipped on a device without client-side encryption, so "Encrypted cloud backup" stays true.
        assertEquals("true", cloud.getAttribute("disableIfNoEncryptionCapabilities"))
        assertEquals(0, cloud.getElementsByTagName("exclude").length)
        checkIncludes(cloud)
        checkIncludes(modern.getElementsByTagName("device-transfer").item(0) as Element)
        checkIncludes(xml("app/src/main/res/xml-v28/full_backup_content.xml"))
    }

    private fun checkExcludes(parent: Element) {
        assertEquals(0, parent.getElementsByTagName("include").length)
        val nodes = parent.getElementsByTagName("exclude")
        val domains = (0 until nodes.length).map { index ->
            val item = nodes.item(index) as Element
            assertEquals(".", item.getAttribute("path"))
            item.getAttribute("domain")
        }
        assertEquals(
            setOf(
                "root", "file", "database", "sharedpref", "external",
                "device_root", "device_file", "device_database", "device_sharedpref",
            ),
            domains.toSet(),
        )
        assertEquals(9, domains.size)
    }

    private fun checkIncludes(parent: Element, flags: String = "") {
        val nodes = parent.getElementsByTagName("include")
        val includes = (0 until nodes.length).map { index ->
            val item = nodes.item(index) as Element
            assertEquals("file", item.getAttribute("domain"))
            assertEquals(flags, item.getAttribute("requireFlags"))
            item.getAttribute("path")
        }
        assertEquals(setOf("cassini.db", "notes/", "voices/", "backup/manifest.json"), includes.toSet())
        assertEquals(4, includes.size)
        assertFalse(includes.any { it.contains("models") || it.contains("settings") || it.contains("no_backup") })
    }
}
