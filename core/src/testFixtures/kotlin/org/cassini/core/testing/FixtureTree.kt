package org.cassini.core.testing

import org.cassini.core.format.OggOpus
import java.io.File
import java.security.MessageDigest

/**
 * A committed tree under fixtures/filesdir, copied into a scratch files root as an upgraded phone would hold it.
 *
 * Committed trees name the emulator's files root ([DEVICE_ROOT]) in uris and paths. [materialise] rewrites that
 * prefix in library.json, library.json.new, session.json and each checkpoint.json, renames every
 * processing-checkpoints/<key> to the sha256 of its rewritten `source` string (the key ProcessingCheckpoint derives),
 * and restores modification times from the tree's .mtimes manifest so checkpoint signatures (length:mtime) validate.
 * Everything else is copied byte for byte. fixtures/README.md describes the trees.
 */
class FixtureTree private constructor(val root: File, val checkpoints: Map<String, File>) {
    companion object {
        const val DEVICE_ROOT = "/data/user/0/org.cassini.android/files"
        private val rewritten = setOf("library.json", "library.json.new", "session.json")
        private val sourceMember = Regex("\"source\":\"([^\"]*)\"")

        fun directory(): File = File(System.getProperty("cassini.fixtures", "../fixtures")!!, "filesdir")

        /** The tree's .mtimes manifest: one "<relative path><TAB><mtime ms>" line per file. */
        fun manifest(tree: File): Map<String, Long> {
            val lines = File(tree, ".mtimes").readLines().filter { it.isNotBlank() }
            return lines.associate { line ->
                val (path, mtime) = line.split('\t')
                path to mtime.toLong()
            }
        }

        fun key(source: String): String = OggOpus.hex(MessageDigest.getInstance("SHA-256").digest(source.toByteArray()))

        fun materialise(name: String, root: File): FixtureTree {
            val tree = File(directory(), name)
            require(tree.isDirectory) { "Missing fixture tree $tree" }
            val target = root.absoluteFile
            // Uri.fromFile would percent-encode anything else, and the rewritten uris would no longer match.
            require(target.path.matches(Regex("[A-Za-z0-9/._-]+"))) { "Unsupported fixture root $target" }
            fun rewrite(text: String) = text.replace(DEVICE_ROOT, target.path)
                .replace(DEVICE_ROOT.replace("/", "\\/"), target.path.replace("/", "\\/"))
            val renamed = mutableMapOf<String, String>()
            val checkpoints = mutableMapOf<String, File>()
            File(tree, "processing-checkpoints").listFiles().orEmpty().forEach { directory ->
                val text = rewrite(File(directory, "checkpoint.json").readText())
                val source = sourceMember.find(text)!!.groupValues[1].replace("\\/", "/")
                renamed[directory.name] = key(source)
                checkpoints[source] = File(target, "processing-checkpoints/${key(source)}")
            }
            fun destination(path: String): String {
                val parts = path.split('/')
                return if (parts.size > 2 && parts[0] == "processing-checkpoints") {
                    (listOf(parts[0], renamed.getValue(parts[1])) + parts.drop(2)).joinToString("/")
                } else {
                    path
                }
            }
            val times = manifest(tree)
            tree.walkTopDown().filter { it.isFile && it.name != ".mtimes" && it.name != ".keep" }.forEach { file ->
                val path = file.relativeTo(tree).invariantSeparatorsPath
                val copy = File(target, destination(path))
                copy.parentFile?.mkdirs()
                if (path in rewritten || file.name == "checkpoint.json") {
                    val text = rewrite(file.readText())
                    copy.writeText(text)
                } else {
                    file.copyTo(copy)
                }
                val mtime = requireNotNull(times[path]) { "$path is missing from $name/.mtimes" }
                check(copy.setLastModified(mtime)) { "Could not set the modification time of $copy" }
            }
            return FixtureTree(target, checkpoints)
        }
    }
}
