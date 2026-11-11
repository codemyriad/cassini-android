package io.codemyriad.cassini.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(".backup.CassiniBackupAgent", attribute("backupAgent"))
        assertEquals("@xml/full_backup_content", attribute("fullBackupContent"))
        assertEquals("@xml/data_extraction_rules", attribute("dataExtractionRules"))
    }

    @Test fun bothXmlGenerationsIncludeOnlyTheLibraryCeilingInCredentialProtectedFiles() {
        val legacy = xml("app/src/main/res/xml/full_backup_content.xml")
        checkIncludes(legacy)
        val modern = xml("app/src/main/res/xml/data_extraction_rules.xml")
        checkIncludes(modern.getElementsByTagName("cloud-backup").item(0) as Element)
        checkIncludes(modern.getElementsByTagName("device-transfer").item(0) as Element)
    }

    private fun checkIncludes(parent: Element) {
        val nodes = parent.getElementsByTagName("include")
        val includes = (0 until nodes.length).map { index ->
            val item = nodes.item(index) as Element
            assertEquals("file", item.getAttribute("domain"))
            item.getAttribute("path")
        }
        assertEquals(setOf("cassini.db", "notes/", "voices/", "backup/manifest.json"), includes.toSet())
        assertEquals(4, includes.size)
        assertFalse(includes.any { it.contains("models") || it.contains("settings") || it.contains("no_backup") })
    }
}
