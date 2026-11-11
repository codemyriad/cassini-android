package io.codemyriad.cassini.data.backup

import android.util.Log
import io.codemyriad.cassini.data.Threads
import io.codemyriad.cassini.data.notes.NoteFiles
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/** Replaces only library-owned roots. A durable journal lets the next cold start roll an interrupted swap back. */
class LibraryRestoreTransaction internal constructor(
    private val live: File,
    private val staging: File,
    private val afterMove: () -> Unit,
    private val afterCommit: () -> Unit,
) {
    constructor(live: File, staging: File) : this(live, staging, {}, {})

    private val journal = RestoreJournal(staging)

    /** Call before opening Room. Never open a partially replaced catalogue. */
    fun recover() {
        Threads.assertOffMain("LibraryRestoreTransaction.recover")
        val value = journal.read() ?: run {
            RestoreStaging.cleanupOrphans(staging)
            return
        }
        require(value.opt("version") == 1)
        val name = value.getString("stage")
        require(RestoreStaging.isStage(name))
        val stage = File(staging, name)
        val phase = value.getString("phase")
        require(phase in setOf("received", "applying", "committed", "rolledBack"))
        when (phase) {
            "applying" -> rollback(stage, value)
            "received" -> Unit
            else -> cleanup(stage)
        }
    }

    private fun rollback(stage: File, value: JSONObject) {
        require(Files.isDirectory(stage.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Missing restore staging" }
        val original = value.getJSONArray("original").let { array ->
            (0 until array.length()).map { array.getString(it) }.toSet()
        }
        require(original.all { it in ROOTS })
        for (root in ROOTS.reversed()) {
            val target = File(live, root)
            val old = File(File(stage, "old"), root)
            val replacement = File(File(stage, replacementName(value)), root)
            if (exists(old)) {
                if (exists(target)) move(target, replacement)
                move(old, target)
            } else if (root !in original && exists(target)) {
                move(target, replacement)
            }
        }
        NoteFiles.syncDirectory(live)
        if (value.optBoolean("deferred")) {
            journal.write(value.put("phase", "received"))
        } else {
            journal.write(value.put("phase", "rolledBack"))
            cleanup(stage)
        }
    }

    /** Constant-size completion handoff. No payload reads, catalogue work, traversal or deletion in this callback. */
    fun complete(stage: File) {
        Threads.assertOffMain("LibraryRestoreTransaction.complete")
        require(stage.parentFile == staging && RestoreStaging.isStage(stage.name))
        requireIdle()
        val value = JSONObject()
            .put("version", 1)
            .put("stage", stage.name)
            .put("phase", "received")
            .put("deferred", true)
        journal.write(value)
    }

    internal fun requireIdle() {
        require(journal.read() == null) { "Unrecovered library restore" }
    }

    /** Only startup may turn this received transaction into a validated replacement. */
    internal fun receivedStage(): File? {
        val value = journal.read() ?: return null
        return if (value.getString("phase") == "received") {
            require(value.opt("version") == 1 && value.optBoolean("deferred"))
            val name = value.getString("stage")
            require(RestoreStaging.isStage(name))
            File(staging, name)
        } else {
            null
        }
    }

    /** Incoming data has already passed completeness and catalogue validation, and Room is closed. */
    fun install(stage: File) {
        Threads.assertOffMain("LibraryRestoreTransaction.install")
        require(stage.parentFile == staging && RestoreStaging.isStage(stage.name))
        val pending = receivedStage()
        val deferred = pending != null
        if (deferred) require(pending == stage) else requireIdle()
        val original = ROOTS.filter { exists(File(live, it)) }
        require(original.none { Files.isSymbolicLink(File(live, it).toPath()) }) { "Linked library root" }
        Files.createDirectories(File(stage, "old").toPath())
        Files.createDirectories(live.toPath())
        val value = JSONObject().put("version", 1).put("stage", stage.name).put("original", JSONArray(original))
            .put("phase", "applying").put("deferred", deferred)
        journal.write(value)
        try {
            for (root in ROOTS) {
                val target = File(live, root)
                if (exists(target)) {
                    move(target, File(File(stage, "old"), root))
                    afterMove()
                }
                val replacement = File(File(stage, replacementName(value)), root)
                if (exists(replacement)) {
                    move(replacement, target)
                    afterMove()
                }
            }
            NoteFiles.syncDirectory(live)
            journal.write(value.put("phase", "committed"))
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

    private fun replacementName(value: JSONObject): String =
        if (value.optBoolean("deferred")) "prepared" else "incoming"

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

    /** Cleanup failure cannot turn an already committed restore into a reported failed restore. */
    private fun cleanup(stage: File) {
        try {
            RestoreStaging.deleteStage(stage)
            Files.deleteIfExists(journal.file.toPath())
            NoteFiles.syncDirectory(staging)
        } catch (error: IOException) {
            Log.w("LibraryRestore", "Restore cleanup deferred", error)
        }
    }

    companion object {
        val ROOTS = listOf("notes", "voices", "cassini.db", "cassini.db-wal", "cassini.db-shm", "cassini.db-journal")
    }
}
