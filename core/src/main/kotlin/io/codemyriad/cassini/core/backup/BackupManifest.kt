package io.codemyriad.cassini.core.backup

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

/** A complete library inventory, separate from OS transport framing. No model or consent/settings path is eligible. */
data class BackupManifest(val files: List<Entry>) {
    data class Entry(val path: String, val bytes: Long, val sha256: String)

    fun json(): String = JSONObject().put("version", VERSION).put(
        "files",
        JSONArray(files.map { JSONObject().put("path", it.path).put("bytes", it.bytes).put("sha256", it.sha256) }),
    ).toString()

    /** Refuse missing, changed or unexpected payloads before any live data can be replaced. */
    fun verify(root: File) {
        val actual = ArrayList<Entry>()
        Files.walk(root.toPath()).use { stream ->
            val iterator = stream.iterator()
            while (iterator.hasNext()) verifyFile(root, iterator.next(), actual)
        }
        require(actual.sortedBy { it.path } == files) { "Incomplete or changed library backup" }
    }

    private fun verifyFile(root: File, file: Path, actual: MutableList<Entry>) {
        val path = root.toPath().relativize(file).toString().replace(File.separatorChar, '/')
        if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
            require(directory(path)) { "Unexpected backup directory" }
            return
        }
        require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) { "Non-file backup payload" }
        if (path == MANIFEST) return
        require(admits(path) && actual.size < MAX_ENTRIES) { "Unexpected backup file" }
        val size = Files.size(file)
        require(size in 0..MAX_FILE_BYTES) { "Oversized backup file" }
        actual += Entry(path, size, sha256(file.toFile()))
    }

    companion object {
        const val MANIFEST = "backup/manifest.json"
        const val MAX_ENTRIES = 100_000
        const val MAX_MANIFEST_BYTES = 32 * 1024 * 1024
        const val MAX_FILE_BYTES = 4L * 1024 * 1024 * 1024
        private const val VERSION = 1
        private const val LEAF_COMPONENTS = 3
        private val id = Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,127}")
        private val audio = Regex("(capture|source)\\.[a-z0-9]{1,5}")
        private val hash = Regex("[0-9a-f]{64}")

        /** Only canonical audio and committed voice state; job work, partial downloads and all other roots are out. */
        fun admits(path: String): Boolean {
            val parts = path.split('/')
            return when {
                path in setOf("cassini.db", "voices/people.json", "voices/generation") -> true
                parts.size != LEAF_COMPONENTS -> false
                parts[0] == "notes" && id.matches(parts[1]) ->
                    parts[2] == "document.opus" || audio.matches(parts[2]) && !parts[2].endsWith(".tmp")
                else -> parts[0] == "voices" && parts[1] == "notes" && parts[2].endsWith(".json") &&
                    id.matches(parts[2].removeSuffix(".json"))
            }
        }

        fun directory(path: String): Boolean {
            if (path in setOf("", "notes", "voices", "voices/notes", "backup")) return true
            val parts = path.split('/')
            return parts.size == 2 && parts[0] == "notes" && id.matches(parts[1])
        }

        fun create(root: File): BackupManifest = BackupManifest(inventory(root))

        fun parse(text: String): BackupManifest {
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_MANIFEST_BYTES) { "Oversized backup inventory" }
            val json = JSONObject(text)
            require(json.opt("version") == VERSION) { "Unsupported backup inventory" }
            val array = json.getJSONArray("files")
            require(array.length() <= MAX_ENTRIES) { "Too many backup files" }
            val entries = (0 until array.length()).map { index ->
                val row = array.getJSONObject(index)
                val path = row.getString("path")
                val rawBytes = row.opt("bytes")
                require(rawBytes is Int || rawBytes is Long) { "Invalid backup size" }
                val bytes = (rawBytes as Number).toLong()
                val sha = row.getString("sha256")
                require(admits(path) && bytes in 0..MAX_FILE_BYTES && hash.matches(sha)) { "Invalid backup entry" }
                Entry(path, bytes, sha)
            }
            require(entries.map { it.path }.toSet().size == entries.size) { "Duplicate backup entry" }
            return BackupManifest(entries.sortedBy { it.path })
        }

        private fun inventory(root: File): List<Entry> {
            val entries = ArrayList<Entry>()
            for (name in listOf("cassini.db", "notes", "voices")) {
                val start = File(root, name).toPath()
                if (Files.notExists(start, LinkOption.NOFOLLOW_LINKS)) continue
                Files.walk(start).use { stream ->
                    val iterator = stream.iterator()
                    while (iterator.hasNext()) {
                        inventoryFile(root, iterator.next(), entries)
                    }
                }
            }
            return entries.sortedBy { it.path }
        }

        private fun inventoryFile(root: File, file: Path, entries: MutableList<Entry>) {
            val path = root.toPath().relativize(file).toString().replace(File.separatorChar, '/')
            require(!Files.isSymbolicLink(file) || !directory(path)) { "Linked backup directory" }
            if (!admits(path)) return
            require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) { "Non-file backup payload" }
            val size = Files.size(file)
            require(size in 0..MAX_FILE_BYTES && entries.size < MAX_ENTRIES) { "Backup inventory limit" }
            entries += Entry(path, size, sha256(file.toFile()))
        }

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** Bound untrusted metadata before JSON allocates its object graph. */
        fun read(file: File): BackupManifest {
            val bytes = ByteArrayOutputStream()
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    if (bytes.size().toLong() + size > MAX_MANIFEST_BYTES) throw IOException("Oversized backup inventory")
                    bytes.write(buffer, 0, size)
                }
            }
            return parse(bytes.toString(Charsets.UTF_8.name()))
        }
    }
}
