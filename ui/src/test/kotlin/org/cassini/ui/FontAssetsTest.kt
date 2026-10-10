package org.cassini.ui

import org.json.JSONArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * The bundled fonts match third_party/fonts/fonts.json and their licences ship unchanged (docs/design/ui.md
 * section 3). Plain JVM: ui/build.gradle.kts passes the repository root as the cassini.repoRoot property.
 */
class FontAssetsTest {
    private val root = File(
        requireNotNull(System.getProperty("cassini.repoRoot")) { "cassini.repoRoot is not set" },
    )
    private val fontDir = File(root, "ui/src/main/res/font")
    private val licenceDir = File(root, "ui/src/main/assets/licenses/fonts")
    private val thirdParty = File(root, "third_party/fonts")

    private data class Entry(val file: String, val family: String, val weight: Int, val sha256: String, val license: String)

    private val entries: List<Entry> by lazy {
        val array = JSONArray(File(thirdParty, "fonts.json").readText())
        (0 until array.length()).map { index ->
            val json = array.getJSONObject(index)
            assertEquals(
                "fonts.json entry $index keys",
                MANIFEST_KEYS,
                json.keys().asSequence().toSortedSet(),
            )
            Entry(
                file = json.getString("file"),
                family = json.getString("family"),
                weight = json.getInt("weight"),
                sha256 = json.getString("sha256"),
                license = json.getString("license"),
            )
        }
    }

    @Test fun manifestListsTheSixFonts() {
        assertEquals(
            listOf(400, 500, 600, 700).map { "Instrument Sans" to it }.toSet() +
                listOf(400, 500).map { "IBM Plex Mono" to it },
            entries.map { it.family to it.weight }.toSet(),
        )
        assertEquals(entries.size, entries.map { it.file }.toSet().size)
        entries.forEach { entry ->
            assertTrue("${entry.file} is not a ui_*.ttf resource name", RESOURCE_NAME.matches(entry.file))
            assertTrue("${entry.license} is missing", File(thirdParty, entry.license).isFile)
        }
    }

    @Test fun everyManifestFontIsBundledWithItsHash() {
        entries.forEach { entry ->
            val font = File(fontDir, entry.file)
            assertTrue("${entry.file} is missing; run scripts/fetch-fonts.sh", font.isFile)
            assertEquals("SHA-256 of ${entry.file}", entry.sha256, sha256(font))
        }
    }

    @Test fun everyBundledFontIsInTheManifest() {
        val bundled = fontDir.listFiles().orEmpty().map { it.name }.toSet()
        assertEquals(entries.map { it.file }.toSet(), bundled)
    }

    @Test fun licenceCopiesAreByteIdentical() {
        assertEquals(LICENCE_COPIES.keys, licenceDir.listFiles().orEmpty().map { it.name }.toSet())
        assertEquals(LICENCE_COPIES.values.toSet(), entries.map { it.license }.toSet())
        LICENCE_COPIES.forEach { (asset, source) ->
            assertArrayEquals(
                "$asset differs from third_party/fonts/$source",
                File(thirdParty, source).readBytes(),
                File(licenceDir, asset).readBytes(),
            )
        }
    }

    @Test fun noWebFontsUnderAnySourceSet() {
        val webFonts = root.walkTopDown()
            .onEnter { dir -> dir.name !in SKIPPED_DIRS }
            .filter { file -> file.isFile && WEB_FONT.matches(file.name) && file.isUnderSrc() }
            .map { it.relativeTo(root).path }
            .toList()
        assertEquals("Web fonts must never be committed (docs/design/ui.md section 3)", emptyList<String>(), webFonts)
    }

    private fun File.isUnderSrc(): Boolean = "src" in relativeTo(root).invariantSeparatorsPath.split('/').dropLast(1)

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private companion object {
        val MANIFEST_KEYS = sortedSetOf("family", "file", "license", "path", "ref", "repo", "sha256", "weight")
        val RESOURCE_NAME = Regex("ui_[a-z0-9_]+\\.ttf")
        val WEB_FONT = Regex(".*\\.woff2?", RegexOption.IGNORE_CASE)
        val SKIPPED_DIRS = setOf(".git", ".gradle", ".kotlin", ".tools", "build", "node_modules")
        val LICENCE_COPIES = mapOf(
            "InstrumentSans-OFL.txt" to "instrument-sans/OFL.txt",
            "IBMPlexMono-OFL.txt" to "ibm-plex-mono/LICENSE.txt",
        )
    }
}
