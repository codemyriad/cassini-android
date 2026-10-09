package org.cassini.android

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** The APK carries copies of the repository's notices and native source manifest; they must not drift. */
class LicenseAssetsTest {
    private fun same(source: String, asset: String) {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        assertEquals("$asset differs from $source", File(root, source).readText(), File(root, "app/src/main/assets/licenses/$asset").readText())
    }
    @Test fun thirdPartyNoticesMatchTheRepository() = same("THIRD_PARTY_NOTICES.md", "THIRD_PARTY_NOTICES.md")
    @Test fun nativeDependenciesMatchTheBuildManifest() = same("scripts/native-dependencies.json", "native-dependencies.json")
}
