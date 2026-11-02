package org.cassini.android.licenses

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.cassini.core.models.ModelCatalog
import org.cassini.core.models.ModelPart
import org.cassini.ui.licenses.LicenseEntry
import org.cassini.ui.licenses.SOURCE_CODE_URL

/**
 * The text of each Licenses row (features.md section 11), read from the APK's `assets/licenses/` (the app's
 * notices, licences and `PRIVACY.md`, and the font licences that `:ui` adds under `licenses/fonts/`).
 *
 * - Cassini: the GPLv3 text. Third-party notices: `THIRD_PARTY_NOTICES.md`, then the licence files of the
 *   libraries that are neither native nor models (DataStore's protobuf, zstd-jni).
 * - Native dependencies: each entry of `native-dependencies.json` (name, version, source URL), then the licence
 *   files of those entries and the Opus licence.
 * - Model notices: the models section of the notices, then the model host's `NOTICE.txt` (a pinned copy of
 *   https://dist.gocassini.com/NOTICE.txt) and the OpenMDW licence of the diarizer.
 * - Where the models come from: the Network section of `PRIVACY.md` (the model host and the redirect allow-list,
 *   architecture 5.6), then each model's pinned pair on the host and the URL of every file that the app downloads.
 *
 * [read] gives the text of an asset path; [list] gives the names in an asset directory (empty for a file).
 */
class LicenseTexts(private val read: (String) -> String, private val list: (String) -> List<String>) {
    fun text(entry: LicenseEntry): String = when (entry) {
        LicenseEntry.CASSINI -> read("$ROOT/Cassini-GPL-3.0.txt")
        LicenseEntry.THIRD_PARTY -> join(listOf(read(NOTICES)) + otherDirectories().flatMap(::files))
        LicenseEntry.NATIVE -> native()
        LicenseEntry.INSTRUMENT_SANS -> read("$ROOT/fonts/InstrumentSans-OFL.txt")
        LicenseEntry.PLEX_MONO -> read("$ROOT/fonts/IBMPlexMono-OFL.txt")
        LicenseEntry.MODELS -> join(listOf(section(read(NOTICES), MODELS_HEADING)) + MODEL_DIRECTORIES.flatMap(::files))
        LicenseEntry.MODEL_SOURCES -> join(listOf(section(read(PRIVACY), NETWORK_HEADING)) + downloads())
        LicenseEntry.PRIVACY -> read(PRIVACY)
        LicenseEntry.SOURCE_CODE -> SOURCE_CODE_URL
    }

    private fun native(): String {
        val dependencies = nativeDependencies()
        val entries = dependencies.map { dependency ->
            listOfNotNull(dependency.name + dependency.version?.let { " $it" }.orEmpty(), dependency.url)
                .joinToString("\n")
        }
        val names = dependencies.map { it.name }.toSet()
        val licences = directories().filter { it in names }.flatMap(::files) +
            files(OPUS_LICENCE)
        return join(entries + licences)
    }

    private data class Dependency(val name: String, val version: String?, val url: String?)

    private fun nativeDependencies(): List<Dependency> =
        Json.parseToJsonElement(read(NATIVE_LIST)).jsonArray.map { it.jsonObject }.mapNotNull { item ->
            item.string("name")?.let { Dependency(it, item.string("version"), item.string("url")) }
        }

    private fun JsonObject.string(key: String): String? = get(key)?.jsonPrimitive?.content

    /** The directories under `licenses/` that hold licence files of libraries, not of fonts or models. */
    private fun directories(): List<String> = list(ROOT).filter { list("$ROOT/$it").isNotEmpty() }.sorted()

    private fun otherDirectories(): List<String> {
        val native = nativeDependencies().map { it.name }.toSet()
        return directories().filter { it !in native && it != FONTS && it !in MODEL_DIRECTORIES }
    }

    /** Every file under [path] (relative to `licenses/`), each as its path and its text; a file gives itself. */
    private fun files(path: String): List<String> {
        val children = list("$ROOT/$path")
        if (children.isEmpty()) return listOf("$path\n\n${read("$ROOT/$path").trim()}")
        return children.sorted().flatMap { files("$path/$it") }
    }

    /**
     * The manifest, then each model's pinned pair (manifest id and source SHA-256) and each of its downloaded files
     * with its URL on the model host, part by part.
     */
    private fun downloads(): List<String> = listOf(ModelCatalog.DIST_MANIFEST_URL) +
        ModelPart.entries.flatMap { part -> part.models }.map { model ->
            val files = model.artifacts.mapNotNull { it.download }.map { "${it.name}\n${it.url}" }
            (listOf("${model.id} ${model.sourceSha256}") + files).joinToString("\n")
        }

    private fun join(parts: List<String>): String = parts.map(String::trim).filter(String::isNotEmpty)
        .joinToString("\n\n")

    companion object {
        const val ROOT = "licenses"
        const val NOTICES = "$ROOT/THIRD_PARTY_NOTICES.md"
        const val PRIVACY = "$ROOT/PRIVACY.md"
        private const val NATIVE_LIST = "$ROOT/native-dependencies.json"
        private const val OPUS_LICENCE = "opus-COPYING.txt"

        /** The model host's notices first, then the licence the diarizer asks to keep with it. */
        private val MODEL_DIRECTORIES = listOf("dist.gocassini.com", "nemotron-3-diarization")
        private const val FONTS = "fonts"
        private const val MODELS_HEADING = "## Downloaded models"
        private const val NETWORK_HEADING = "## Network"

        /**
         * The Markdown section that starts with the heading line [heading] (a prefix), up to the next `## `. The
         * heading is shown as plain text, without its `#` marks.
         */
        fun section(markdown: String, heading: String): String {
            val lines = markdown.lines()
            val start = lines.indexOfFirst { it.startsWith(heading) }
            if (start < 0) return ""
            val end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("## ") } ?: lines.size
            val title = lines[start].trimStart('#').trim()
            return (listOf(title) + lines.subList(start + 1, end)).joinToString("\n").trim()
        }
    }
}
