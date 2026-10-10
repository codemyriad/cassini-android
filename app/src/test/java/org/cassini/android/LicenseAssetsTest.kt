package org.cassini.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The APK carries copies of the repository's notices, privacy policy and native source manifest; they must not drift. */
class LicenseAssetsTest {
    private val root = generateSequence(File("").absoluteFile, File::getParentFile)
        .first { dir -> File(dir, "settings.gradle.kts").exists() }

    private fun same(source: String, asset: String) {
        val copy = File(root, "app/src/main/assets/licenses/$asset")
        assertEquals("$asset differs from $source", File(root, source).readText(), copy.readText())
    }

    @Test fun thirdPartyNoticesMatchTheRepository() = same("THIRD_PARTY_NOTICES.md", "THIRD_PARTY_NOTICES.md")

    // TODO(P10): app/build.gradle.kts must list the root PRIVACY.md in the licenseSources test inputs,
    // or an edit of only that file leaves this test up to date or takes it from the build cache.
    @Test fun privacyPolicyMatchesTheRepository() = same("PRIVACY.md", "PRIVACY.md")

    @Test fun nativeDependenciesMatchTheBuildManifest() = same("scripts/native-dependencies.json", "native-dependencies.json")

    @Test fun noticesNameTheBundledFontsAndTheirLicence() {
        val notices = File(root, "THIRD_PARTY_NOTICES.md").readText()
        for (name in listOf("Instrument Sans", "IBM Plex Mono", "SIL Open Font License")) {
            assertTrue("THIRD_PARTY_NOTICES.md does not name $name", name in notices)
        }
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
