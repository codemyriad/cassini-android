package io.codemyriad.cassini.data.backup

import android.util.AtomicFile
import android.util.Log
import io.codemyriad.cassini.data.Threads
import io.codemyriad.cassini.data.notes.NoteFiles
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** Replaces only library-owned roots. A durable journal lets the next cold start roll an interrupted swap back. */
class LibraryRestoreTransaction internal constructor(
    private val live: File,
    private val staging: File,
    private val afterMove: () -> Unit,
    private val afterCommit: () -> Unit,
) {
    constructor(live: File, staging: File) : this(live, staging, {}, {})

    private val journal = File(staging, "applying.json")

    /** Call before opening Room. Never open a partially replaced catalogue. */
    fun recover() {
        Threads.assertOffMain("LibraryRestoreTransaction.recover")
        val value = readJournal() ?: run {
            cleanupOrphans()
            return
        }
        require(value.opt("version") == 1)
        val name = value.getString("stage")
        require(STAGE.matches(name))
        val stage = File(staging, name)
        val phase = value.getString("phase")
        require(phase in setOf("applying", "committed", "rolledBack"))
        if (phase != "applying") {
            cleanup(stage)
            return
        }
        require(Files.isDirectory(stage.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Missing restore staging" }
        val original = value.getJSONArray("original").let { array ->
            (0 until array.length()).map { array.getString(it) }.toSet()
        }
        require(original.all { it in ROOTS })
        for (root in ROOTS.reversed()) {
            val target = File(live, root)
            val old = File(File(stage, "old"), root)
            val replacement = File(File(stage, "incoming"), root)
            if (exists(old)) {
                if (exists(target)) move(target, replacement)
                move(old, target)
            } else if (root !in original && exists(target)) {
                move(target, replacement)
            }
        }
        NoteFiles.syncDirectory(live)
        writeJournal(value.put("phase", "rolledBack"))
        cleanup(stage)
    }

    /** Incoming data has already passed completeness and catalogue validation, and Room is closed. */
    fun install(stage: File) {
        Threads.assertOffMain("LibraryRestoreTransaction.install")
        require(stage.parentFile == staging && STAGE.matches(stage.name))
        require(readJournal() == null) { "Unrecovered library restore" }
        val original = ROOTS.filter { exists(File(live, it)) }
        require(original.none { Files.isSymbolicLink(File(live, it).toPath()) }) { "Linked library root" }
        Files.createDirectories(File(stage, "old").toPath())
        Files.createDirectories(live.toPath())
        val value = JSONObject().put("version", 1).put("stage", stage.name).put("original", JSONArray(original))
            .put("phase", "applying")
        writeJournal(value)
        try {
            for (root in ROOTS) {
                val target = File(live, root)
                if (exists(target)) {
                    move(target, File(File(stage, "old"), root))
                    afterMove()
                }
                val replacement = File(File(stage, "incoming"), root)
                if (exists(replacement)) {
                    move(replacement, target)
                    afterMove()
                }
            }
            NoteFiles.syncDirectory(live)
            writeJournal(value.put("phase", "committed"))
            afterCommit()
            cleanup(stage)
        } catch (error: IOException) {
            try {
                recover()
            } catch (@Suppress("TooGenericExceptionCaught") recovery: Exception) {
                error.addSuppressed(recovery)
            }
            throw error
        }
    }

    private fun writeJournal(value: JSONObject) {
        val atomic = AtomicFile(journal)
        val stream = atomic.startWrite()
        try {
            val payload = value.toString()
            val wrapper = JSONObject().put("payload", payload).put("sha256", checksum(payload))
            stream.write(wrapper.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
            NoteFiles.syncDirectory(staging)
        } catch (error: IOException) {
            atomic.failWrite(stream)
            throw error
        }
    }

    private fun move(from: File, to: File) {
        require(!exists(to)) { "Ambiguous restore recovery" }
        Files.createDirectories(to.parentFile!!.toPath())
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
        NoteFiles.syncDirectory(from.parentFile!!)
        NoteFiles.syncDirectory(to.parentFile!!)
    }

    private fun exists(file: File): Boolean = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        true
    } catch (_: NoSuchFileException) {
        false
    }

    private fun readJournal(): JSONObject? {
        val bytes = ByteArray(MAX_JOURNAL_BYTES + 1)
        val size = try {
            AtomicFile(journal).openRead().use { input ->
                readBounded(input, bytes)
            }
        } catch (error: FileNotFoundException) {
            if (Files.notExists(journal.toPath()) && Files.notExists(File(journal.path + ".bak").toPath())) return null
            throw error
        }
        require(size < bytes.size) { "Oversized restore journal" }
        val wrapper = JSONObject(String(bytes, 0, size, Charsets.UTF_8))
        val payload = wrapper.getString("payload")
        require(checksum(payload) == wrapper.getString("sha256")) { "Damaged restore journal" }
        return JSONObject(payload)
    }

    private fun readBounded(input: InputStream, bytes: ByteArray): Int {
        var used = 0
        while (used < bytes.size) {
            val count = input.read(bytes, used, bytes.size - used)
            if (count < 0) break
            used += count
        }
        return used
    }

    /** Cleanup failure cannot turn an already committed restore into a reported failed restore. */
    private fun cleanup(stage: File) {
        try {
            deleteStage(stage)
            Files.deleteIfExists(journal.toPath())
            NoteFiles.syncDirectory(staging)
        } catch (error: IOException) {
            Log.w("LibraryRestore", "Restore cleanup deferred", error)
        }
    }

    private fun cleanupOrphans() {
        if (Files.notExists(staging.toPath())) return
        try {
            Files.newDirectoryStream(staging.toPath()).use { directories ->
                for (path in directories) {
                    cleanupOrphan(path)
                }
            }
        } catch (error: IOException) {
            Log.w("LibraryRestore", "Unused restore staging cleanup deferred", error)
        }
    }

    private fun cleanupOrphan(path: Path) {
        if (STAGE.matches(path.fileName.toString())) deleteStage(path.toFile())
    }

    companion object {
        private fun checksum(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        private const val MAX_JOURNAL_BYTES = 4096
        val ROOTS = listOf("notes", "voices", "cassini.db", "cassini.db-wal", "cassini.db-shm", "cassini.db-journal")
        private val STAGE = Regex("restore-[a-f0-9-]{36}")

        /** Only this feature's private staging tree, never a caller's library or model root. */
        fun deleteStage(stage: File) {
            require(STAGE.matches(stage.name))
            if (!stage.exists()) return
            Files.walk(stage.toPath()).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
            }
        }
    }
}
