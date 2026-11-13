package io.codemyriad.cassini

import io.codemyriad.cassini.core.models.ModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** The APK carries copies of the repository's notices, privacy policy and native source manifest; they must not drift. */
class LicenseAssetsTest {
    private val root = generateSequence(File("").absoluteFile, File::getParentFile)
        .first { dir -> File(dir, "settings.gradle.kts").exists() }

    private fun same(source: String, asset: String) {
        val copy = File(root, "app/src/main/assets/licenses/$asset")
        assertEquals("$asset differs from $source", File(root, source).readText(), copy.readText())
    }

    @Test fun thirdPartyNoticesMatchTheRepository() = same("THIRD_PARTY_NOTICES.md", "THIRD_PARTY_NOTICES.md")

    @Test fun privacyPolicyMatchesTheRepository() = same("PRIVACY.md", "PRIVACY.md")

    @Test fun bundledPrivacyDescribesDefaultDirectTransferAndItsExclusions() {
        val privacy = File(root, "app/src/main/assets/licenses/PRIVACY.md").readText()
        for (text in listOf(
            "On Android 9 or later, direct device-to-device transfer copies your notes, the catalogue and saved voices",
            "when “Move to a new phone” is on",
            "This choice is on by default",
            "Cloud backup is not used",
            "Downloaded models and unfinished work are not copied",
        )) assertTrue("Missing transfer disclosure: $text", text in privacy)
        assertTrue("Obsolete transfer denial", "Android does not back up the Cassini data" !in privacy)
    }

    @Test fun privacyPolicyListsEachDownloadHost() {
        val privacy = File(root, "PRIVACY.md").readText()
        for (host in ModelCatalog.DOWNLOAD_HOSTS) {
            assertTrue("PRIVACY.md does not list $host", "- `$host`" in privacy)
        }
    }

    /** The model host's notices, as https://dist.gocassini.com/NOTICE.txt served them on 2026-11-01, byte for byte. */
    @Test fun theModelHostNoticeIsThePinnedCopy() {
        val notice = File(root, "app/src/main/assets/licenses/dist.gocassini.com/NOTICE.txt").readBytes()
        val sha256 = MessageDigest.getInstance("SHA-256").digest(notice).joinToString("") { "%02x".format(it) }
        assertEquals("8a153125fcc23e60ee52ec06a1af64436d568eaef0c8eeffc83c0f365f4621b8", sha256)
    }

    @Test fun nativeDependenciesMatchTheBuildManifest() = same("scripts/native-dependencies.json", "native-dependencies.json")

    @Test fun noticesDescribeTheSystemFontWithoutClaimingBundledFonts() {
        val notices = File(root, "THIRD_PARTY_NOTICES.md").readText()
        assertTrue("Android system font" in notices)
        assertTrue("No font files are bundled" in notices)
        assertTrue("Instrument Sans" !in notices && "IBM Plex Mono" !in notices)
    }

    @Test fun dataStoreProtobufLicenceShipsWithTheNotices() {
        val notices = File(root, "THIRD_PARTY_NOTICES.md").readText()
        assertTrue(
            "THIRD_PARTY_NOTICES.md does not name the DataStore protobuf copy",
            "datastore-preferences-external-protobuf" in notices,
        )
        val licence = File(root, "app/src/main/assets/licenses/protobuf/LICENSE").readText()
        assertTrue("protobuf/LICENSE has no copyright line", licence.startsWith("Copyright 2008 Google Inc."))
    }
}
